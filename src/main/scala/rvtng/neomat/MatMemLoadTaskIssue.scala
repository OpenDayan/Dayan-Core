package rvtng.neomat

import chisel3._
import chisel3.util._
import rvtng.utils.PartialAssign
import rvtng.uram.{AdvURAMReq, AdvURAMResp, SwizzleAddr}

class MatMemLoadTaskIssue(params: MatParams, srcCount: Int, regIdWidth: Int, posWidth: Int) extends Module {
  val ports = (params.busChannel * params.channelWidth) / 8
  val portsPerChannel = params.channelWidth / 8
  val log2SrcCount = log2Ceil(srcCount)
  val io = IO(new Bundle {
    // URAM 读取内部接口，全速率
    val busRReq = Vec(srcCount, Flipped(Decoupled(new MatInnerTileReadReq(params, regIdWidth, posWidth))))
    val busRResp = Output(Vec(srcCount, Vec(params.busChannel,
      new MatInnerTileChannelReadResp(params, regIdWidth, posWidth))))

    // URAM 读取外部接口，全速率
    val uramReq  = Vec(ports,
      Decoupled(new AdvURAMReq(params.addrWidth, 1 + regIdWidth + posWidth + log2SrcCount, 64)))
    val uramResp = Flipped(Vec(params.busChannel,
      Decoupled(Vec(portsPerChannel, new AdvURAMResp(params.addrWidth, 1 + regIdWidth + posWidth + log2SrcCount, 64)))))

    // 响应接口，完成一次请求
    val busRAck = Output(Vec(srcCount, Bool()))
  })
  // 仲裁机制，仲裁内部 URAM 请求
  val arb = Module(new RRArbiter(new MatInnerTileReadReq(params, regIdWidth, posWidth), srcCount)).io
  arb.in <> io.busRReq
  // 总线仲裁后消息包
  class MatInnerArbTileReadReq extends Bundle {
    val regId = UInt(regIdWidth.W)
    val position = UInt(posWidth.W)
    val src = UInt(log2SrcCount.W)
    val addr = UInt(params.addrWidth.W)
    val stride = UInt(params.addrWidth.W)
    val swizzle = UInt(24.W)
    val last = Bool()
  }
  // 将内部 URAM 请求，分派到 8 个 Channel 的处理队列中
  val reqQ = VecInit.tabulate(params.busChannel)(i => {
    Module(new Queue(new MatInnerArbTileReadReq, 4)).io
  })
  for(i <- 0 until params.busChannel) {
    val m = reqQ(i)
    m.enq.valid := arb.out.fire
    PartialAssign(m.enq.bits, arb.out.bits)
    // TODO: 适配 busChannelWidth =/= 主序长度（cK）的情况
    m.enq.bits.addr := arb.out.bits.addr + arb.out.bits.stride * i.U
    m.enq.bits.src := arb.chosen
  }
  arb.out.ready := VecInit(reqQ.map(_.enq.ready)).reduceTree(_ & _)
  // 将 Channel 的处理队列，连接到 uram 接口
  for(i <- 0 until ports) {
    val channel = i / portsPerChannel
    val subPortId = i % portsPerChannel
    if(subPortId == 0) {
      reqQ(channel).deq.ready := io.uramReq(i).ready
    }
    val pld = reqQ(channel).deq.bits
    io.uramReq(i).valid := reqQ(channel).deq.valid
    io.uramReq(i).bits.address := SwizzleAddr(pld.addr + (i * 8).U, pld.swizzle) >> 3
    io.uramReq(i).bits.id := pld.last ## pld.regId ## pld.position ## pld.src
  }
  // 完成队列
  val uramCompleteQ = VecInit.tabulate(params.busChannel)(i => Module(new Queue(UInt(log2SrcCount.W), 16)).io)
  val uramCompleteRdy = VecInit(uramCompleteQ.map(_.deq.valid)).reduceTree(_ & _)
  // 将 URAM 的返回接口，连接到 Channel 的响应端口
  for(i <- 0 until params.busChannel) {
    io.uramResp(i).ready := uramCompleteQ(i).enq.ready
    val pld = io.uramResp(i).bits
    val last = pld(0).id(log2SrcCount + posWidth + regIdWidth)
    val regId = pld(0).id(log2SrcCount + posWidth + regIdWidth - 1, log2SrcCount + posWidth)
    val pos = pld(0).id(log2SrcCount + posWidth - 1, log2SrcCount)
    val src = pld(0).id(log2SrcCount - 1, 0)
    for(s <- 0 until srcCount) {
      io.busRResp(s)(i).valid := (src === s.U) & io.uramResp(i).valid & uramCompleteQ(i).enq.ready
      io.busRResp(s)(i).regId := regId
      io.busRResp(s)(i).position := pos
      io.busRResp(s)(i).data := VecInit(pld.map(_.data)).asTypeOf(io.busRResp(s)(i).data)
    }
    uramCompleteQ(i).enq.valid := io.uramResp(i).valid & last
    uramCompleteQ(i).enq.bits := src
    uramCompleteQ(i).deq.ready := uramCompleteRdy
  }

  // 跟踪所有请求完成情况
  for(i <- 0 until srcCount) {
    io.busRAck(i) := (uramCompleteQ(0).deq.bits === i.U) && uramCompleteRdy
  }
}
