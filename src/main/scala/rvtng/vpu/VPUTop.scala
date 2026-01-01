package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._

import Util._
import rvtng.top.ScoreboardComp
import rvtng.top.TensorInst
import rvtng.top.TensorResp
import rvtng.uram.AdvURAMReq
import rvtng.uram.AdvURAMResp
import rvtng.uram.AdvURAMWResp
import _root_.circt.stage.ChiselStage
import rvtng.uram.Adv2R1WURAM
import rvtng.top._
import rvtng.uram._
import rvtng.mat.MatScoreboard

class VPUTop[T <: Data](params: VpuParams)(implicit ev: Arithmetic[T]) extends Module{
    import params._

    val instReq = IO(Flipped(Decoupled(new TensorInst())))
    //TODO: instResp？ sbcomp？
    val instResp = IO(Decoupled(new TensorResp()))
    val sbComp  = IO(Decoupled(new ScoreboardComp()))

    private val uramPort = (params.laneWidth * 32 / 2) / 64
    private val uramPortPerChannel = params.uramPortPerChannel
    private val uramChannel = uramPort / uramPortPerChannel

    // URAM 读口
    val uramRReq  = IO(Vec(uramPort, Decoupled(
      new AdvURAMReq(params.uramAddrBits, params.sourceBits, 64))))
    val uramRResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
      new AdvURAMResp(params.uramAddrBits,  params.sourceBits, 64))))))

    // URAM 写口
    val uramWReq = IO(Vec(uramPort, Decoupled(
      new AdvURAMReq(params.uramAddrBits, params.sourceBits, 64))))
    val uramWData = IO(Output(Vec(uramPort, UInt(64.W))))
    val uramWResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
        new AdvURAMWResp(params.uramAddrBits,  params.sourceBits, 64))))))

    private val VPU = Module(new VPUwrapper(params))

    private val TU = Module(new TcsrUnit(64, 32, params))

    private val instQueue = Module(new Queue(new GemminiCmd(reservation_station_entries), 32))

    private val rowBytes = row_width / 8
    private val rowPorts = rowBytes / 8
    private val rowChannel = rowPorts/ uramPortPerChannel

    class WriteReq extends Bundle{
      // val writeio = Vec(uramPort, (new AdvURAMReq(params.uramAddrBits, params.sourceBits, 64)))
      val addr = UInt(params.uramAddrBits.W)
      val mask = Vec((row_width / (aligned_to * 8)) max 1, Bool())
      val last = Bool()
      val data = UInt(row_width.W)
    }

    // val WriteReqQueue = Module(new Queue(new WriteReq, 16))
    val wq = VecInit((0 until (rowChannel + 1)).map(
      i => Module(new Queue(new WriteReq, 16)).io
    ))
    val ReqCount = RegInit(VecInit(Seq.fill(2)(0.U(16.W))))
    val current_ReqIndex = RegInit(0.U(1.W))
    val current_RespIndex = RegInit(0.U(1.W))

    val ReadReqQueue = Module(new Queue(UInt(3.W), 16))

    instQueue.io.enq <> TU.io.cmdout

    private val decodeCompReq   = instReq.bits.instWord(63, 56) === "hff".U

    val raw_cmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))
    raw_cmd.valid := instReq.valid && !decodeCompReq
    raw_cmd.bits.cmd.inst := instReq.bits.instWord.asTypeOf(new RoCCInstruction)
    raw_cmd.bits.cmd.rs1 := instReq.bits.rs1Data
    raw_cmd.bits.cmd.rs2 := instReq.bits.rs2Data
    raw_cmd.bits.cmd.rs3 := instReq.bits.rdData
    raw_cmd.bits.rob_id := DontCare
    raw_cmd.bits.tcfgrs := DontCare
    raw_cmd.bits.from_conv_fsm := false.B
    raw_cmd.bits.from_matmul_fsm := false.B
    instReq.ready := raw_cmd.ready

    TU.io.cmdin <> raw_cmd

    //inst resp source: tcsr, sb
    private val sb = Module(new VpuScoreboard(params))

    val respArb = Module(new Arbiter(instResp.bits.cloneType, 2)).io
    respArb.in(0) <> TU.io.instResp
    respArb.in(1) <> sb.instResp

    // 脉冲信号，一条指令完成并提交
    private val commit_pulse = Wire(Bool())

    sb.instReq.bits := instReq.bits
    sb.instReq.valid := instReq.valid & decodeCompReq
    sb.instIssue := instQueue.io.enq.fire
    // sb.instCommit := ReqCount(current_RespIndex) === 0.U && current_RespIndex =/= current_ReqIndex
    // sb.instCommit := VPU.io.done
    sb.instCommit := commit_pulse
    sb.sbComp <> sbComp

    instResp <> respArb.out

    // Gated Flow
    private val vpu_not_yet_commit = RegInit(false.B)

    when(VPU.io.start && !VPU.io.done){
      vpu_not_yet_commit := true.B
    }.elsewhen(commit_pulse){
      vpu_not_yet_commit := false.B
    }

    VPU.io.start := instQueue.io.deq.valid && !vpu_not_yet_commit
    VPU.io.cmd := instQueue.io.deq.bits
    instQueue.io.deq.ready := commit_pulse

    // 阻塞等待所有 Channel 中标记为 Last 的指令完成，再生成提交脉冲信号。
    // 实现：每个 Channel 实现一个 Queue，等待所有 Channel 的 Last 指令都有效后，才发出提交信号。
    private val respLastQueue = VecInit((0 until (rowChannel + 1)).map( c =>
      Module(new Queue(Bool(), 16)).io
    ))
    respLastQueue.zipWithIndex.foreach{ case (q, i) =>
      q.enq.valid := uramWResp(i).valid && uramWResp(i).bits(0).id === 1.U
      q.enq.bits := 0.B
      q.deq.ready := commit_pulse
      uramWResp(i).ready := q.enq.ready
    }
    for(i <- (rowChannel + 1) until uramChannel){
      uramWResp(i).ready := true.B
    }
    commit_pulse := VecInit(respLastQueue.map(q => q.deq.valid)).reduceTree(_ && _)

    // when(current_ReqIndex === current_RespIndex){
    //   when(uramWReq(0).fire && !uramWResp(0).fire){
    //     ReqCount(current_ReqIndex) := ReqCount(current_ReqIndex) + 1.U
    //   }.elsewhen(!uramWReq(0).fire && uramWResp(0).fire){
    //     ReqCount(current_RespIndex) := ReqCount(current_RespIndex) - 1.U
    //   }
    // }.otherwise{
    //   when(uramWReq(0).fire){
    //     ReqCount(current_ReqIndex) := ReqCount(current_ReqIndex) + 1.U
    //   }
    //   when(uramWResp(0).fire){
    //     ReqCount(current_RespIndex) := ReqCount(current_RespIndex) - 1.U
    //   }
    // }

    // when(WriteReqQueue.io.deq.bits.last && WriteReqQueue.io.deq.fire){
    //   current_ReqIndex := !current_ReqIndex
    // }
    // when(ReqCount(current_RespIndex) === 0.U && current_RespIndex =/= current_ReqIndex){
    //   current_RespIndex := !current_RespIndex
    // }


    //TODO: 不同resp端口返回带有的decouple用处
    //TODO: sb没用到

    VPU.io.srams.read.resp.valid := uramRResp(0).valid
    val RespData = VecInit((0 until uramPortPerChannel).map(i => uramRResp(0).bits(i).data)).asUInt
    VPU.io.srams.read.resp.bits.data := Mux(ReadReqQueue.io.deq.bits === 4.U, RespData >> 32.U, RespData)
    VPU.io.srams.read.resp.bits.fromDMA := false.B
    uramRResp(0).ready := VPU.io.srams.read.resp.ready
    ReadReqQueue.io.deq.ready := VPU.io.srams.read.resp.fire

    // VPU.io.srams.write.ready := WriteReqQueue.io.enq.ready
    VPU.io.srams.write.ready := VecInit(wq.map(_.enq.ready)).reduceTree(_ && _)
    //写数据队列不能被阻塞
    // chisel3.assert(!VPU.io.srams.write.valid || (VPU.io.srams.write.valid && WriteReqQueue.io.enq.ready))
    chisel3.assert(!VPU.io.srams.write.valid || (VPU.io.srams.write.valid && VPU.io.srams.write.ready))
    // WriteReqQueue.io.enq.bits.mask := VPU.io.srams.write.mask
    // WriteReqQueue.io.enq.bits.last := VPU.io.done
    // WriteReqQueue.io.enq.valid := VPU.io.srams.write.valid
    // WriteReqQueue.io.enq.bits.data := VPU.io.srams.write.data
    wq.zipWithIndex.foreach(iter => {
      val (q, idx) = iter
      q.enq.bits.addr := VPU.io.srams.write.addr
      q.enq.bits.mask := VPU.io.srams.write.mask
      q.enq.bits.last := VPU.io.done
      q.enq.bits.data := VPU.io.srams.write.data
      q.enq.valid := VPU.io.srams.write.valid && VPU.io.srams.write.ready
      q.deq.ready := uramWReq(idx * uramPortPerChannel).ready
    })

    for( i <- 1 until uramChannel){
      uramRResp(i).ready := true.B
    }

    // for( i <- 0 until uramChannel){
    //   uramWResp(i).ready := true.B
    // }

    // WriteReqQueue.io.deq.ready := uramWReq(0).ready

    val ReadreqValid = VPU.io.srams.read.req.valid && uramRReq(0).ready && ReadReqQueue.io.enq.ready
    VPU.io.srams.read.req.ready := ReadreqValid
    ReadReqQueue.io.enq.valid := VPU.io.srams.read.req.valid && uramRReq(0).ready
    ReadReqQueue.io.enq.bits := VPU.io.srams.read.req.bits.addr(2,0)
    

    for( i <- 0 until uramPort){
      if(i <= rowPorts){
        uramRReq(i).valid := (i != rowPorts).B && VPU.io.srams.read.req.valid && ReadReqQueue.io.enq.ready
        uramRReq(i).bits.address := (VPU.io.srams.read.req.bits.addr + (i * 8).U) >> 3
        uramRReq(i).bits.id := 0.U
        uramRReq(i).bits.byteEnable := 255.U

        // WriteReqQueue.io.enq.bits.writeio(i).address := VPU.io.srams.write.addr
        // WriteReqQueue.io.enq.bits.writeio(i).id := 0.U
        // WriteReqQueue.io.enq.bits.writeio(i).byteEnable := 255.U

        // uramWReq(i).valid := Mux(WriteReqQueue.io.deq.bits.mask(i * 8), WriteReqQueue.io.deq.valid, false.B)
        // 始终有效的写请求，偶尔取消 byteEnable 的置位，用于简化 Resp 追踪的复杂度
        // uramWReq(i).valid := WriteReqQueue.io.deq.valid
        uramWReq(i).valid := wq(i / uramPortPerChannel).deq.valid
        uramWReq(i).bits.address := (wq(i / uramPortPerChannel).deq.bits.addr + (i * 8).U) >> 3
        uramWReq(i).bits.id := wq(i / uramPortPerChannel).deq.bits.last.asUInt

        if(i == 0) {
          uramWReq(i).bits.byteEnable := Mux(wq(i / uramPortPerChannel).deq.bits.addr(2,0) === 4.U,
          Cat(wq(i / uramPortPerChannel).deq.bits.mask.asUInt(3, 0), 0.U(4.W)),
          wq(i / uramPortPerChannel).deq.bits.mask.asUInt(7, 0))
          uramWData(i) := Mux(wq(i / uramPortPerChannel).deq.bits.addr(2,0) === 4.U,
          Cat(wq(i / uramPortPerChannel).deq.bits.data(31, 0), Fill(32, 0.U)),
          wq(i / uramPortPerChannel).deq.bits.data(63, 0))
        } else if(i == rowPorts) {
          uramWReq(i).bits.byteEnable := Mux(wq(i / uramPortPerChannel).deq.bits.addr(2,0) === 4.U,
          Cat(0.U(4.W), wq(i / uramPortPerChannel).deq.bits.mask.asUInt(i * 8 - 1, i * 8 - 4)),
          0.U)
          uramWData(i) := Cat(0.U(32.W), wq(i / uramPortPerChannel).deq.bits.data(i*64 - 1, i*64 - 32))
        } else {
          uramWReq(i).bits.byteEnable := Mux(wq(i / uramPortPerChannel).deq.bits.addr(2,0) === 4.U,
          wq(i / uramPortPerChannel).deq.bits.mask.asUInt(i * 8 + 3, i * 8 - 4),
          wq(i / uramPortPerChannel).deq.bits.mask.asUInt(i * 8 + 7, i * 8))
          uramWData(i) := Mux(wq(i / uramPortPerChannel).deq.bits.addr(2,0) === 4.U,
          wq(i / uramPortPerChannel).deq.bits.data(i*64 + 31, i*64 - 32),
          wq(i / uramPortPerChannel).deq.bits.data(i*64 + 63, i*64))
        }

      }else{
        uramRReq(i) <> DontCare
        uramWReq(i) <> DontCare
        uramWData(i) <> DontCare
        uramRReq(i).valid := false.B 
        uramWReq(i).valid := false.B
        uramWReq(i).bits.byteEnable := 0.U
        // WriteReqQueue.io.enq.bits.writeio(i).address := DontCare
        // WriteReqQueue.io.enq.bits.writeio(i).id := DontCare
        // WriteReqQueue.io.enq.bits.writeio(i).byteEnable := DontCare
      }
      
    }
}

class VPUTestTop[T <: Data](params: VpuParams, name: String = "VecTestTop")(implicit ev: Arithmetic[T]) extends Module {

  override def desiredName: String = name
  private val uramPort = (params.laneWidth * 32 / 2) / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // URAM Instance
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 1, R1Port = 1, W0Port = 1, GroupCount = params.uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = params.sourceBits, dataWidth = 64
  ))

  // DMA Instance
  private val vpu = Module(new VPUTop(params))

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  vpu.instReq <> instReq
  vpu.instResp <> instResp
  vpu.sbComp <> sbComp
  vpu.uramRReq <> uram.r0req_i(0)
  vpu.uramRResp <> uram.r0resp_o(0)
  vpu.uramWReq <> uram.wreq_i(0)
  vpu.uramWResp <> uram.wresp_o(0)
  uram.wdata_i(0) := vpu.uramWData
  uram.r1req_i(0).map(u => {
    u.bits := DontCare
    u.valid := 0.B
  })
  uram.r1resp_o(0).map(u => {
    u.ready := 1.B
  })
}

object VPUTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new VPUTestTop[Float](VpuParams(
    ),
    "VPUTestTop"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/VPUTestTop"
    )
  )
}
