package rvtng.neomat

import chisel3._
import chisel3.util._
import rvtng.utils.PartialAssign
import rvtng.uram.{AdvURAMReq, AdvURAMResp, AdvURAMWResp, SwizzleAddr}

class MatMemStoreTaskIssue(params: MatParams) extends Module {
  private val ports = (params.busWriteChannel * params.channelWidth) / 8
  private val portsPerChannel = params.channelWidth / 8
  val io = IO(new Bundle{
    val busWReq = Vec(params.busWriteChannel,
      Flipped(Decoupled(new MatInnerTileWriteReq(params)))
    )
    val busWResp = Output(Vec(params.busWriteChannel, new MatInnerTileChannelWriteResp))

    val uramWReq   = Vec(ports, Decoupled(new AdvURAMReq(params.addrWidth, 1, 64)))
    val uramWdata  = Output(Vec(ports, UInt(64.W)))
    val uramWResp  = Flipped(Vec(params.busWriteChannel,
      Decoupled(Vec(portsPerChannel, new AdvURAMWResp(params.addrWidth, 1, 64)))))
  })

  // 连接 WReq 到 uramWReq 及 uramWdata
  for(i <- 0 until params.busWriteChannel) {
    val wdata = io.busWReq(i).bits.data.asTypeOf(Vec(portsPerChannel, UInt(64.W)))
    for(p <- 0 until portsPerChannel) {
      io.uramWReq(i * portsPerChannel + p).valid := io.busWReq(i).valid
      io.uramWReq(i * portsPerChannel + p).bits.address :=
        SwizzleAddr(io.busWReq(i).bits.addr + (p * 8).U, io.busWReq(i).bits.swizzle) >> 3
      io.uramWReq(i * portsPerChannel + p).bits.id := io.busWReq(i).bits.last.asUInt
      io.uramWdata(i * portsPerChannel + p) := wdata(p)
    }
    io.busWReq(i).ready := io.uramWReq(i * portsPerChannel).ready
  }

  // 连接 uramWResp 到 WResp
  for(i <- 0 until params.busWriteChannel) {
    io.uramWResp(i).ready := 1.B
    io.busWResp(i).valid := io.uramWResp(i).valid
    io.busWResp(i).last := io.uramWResp(i).bits(0).id.asBool
  }
}
