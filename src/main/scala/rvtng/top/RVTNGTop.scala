package rvtng.top

import chisel3._
import chisel3.util._
import rvtng.uram.Adv2R1WURAM
import rvtng.vec.{VecTop, VecParams}


class RVTNGTop extends Module {
  // 实例化 MAT、Vec、DMA、URAM，并完成顶层指令分发、Scoreboard 实现。

  val vecParams = new VecParams();

  override def desiredName: String = name
  private val uramPort = (vecParams.laneWidth * 32 / 2) / 64
  private val uramPortPerChannel = vecParams.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // Scoreboard Intf
  val sbComp  = Wire(Vec(3, DecoupledIO(new ScoreboardComp())))

  // URAM Instance
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 1, R1Port = 1, W0Port = 1, GroupCount = vecParams.uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = vecParams.sourceBits, dataWidth = 64
  ))

  // VEC Instance
  private val vec = Module(new VecTop(vecParams))

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))

  vec.instReq <> instReq
  vec.instResp <> instResp
  vec.sbComp <> sbComp(1)
  vec.uramRReq <> uram.r0req_i(0)
  vec.uramRResp <> uram.r0resp_o(0)
  vec.uramWReq <> uram.wreq_i(0)
  vec.uramWResp <> uram.wresp_o(0)
  uram.wdata_i(0) := vec.uramWData
  uram.r1req_i(0).map(u => {
    u.bits := DontCare
    u.valid := 0.B
  })
  uram.r1resp_o(0).map(u => {
    u.ready := 1.B
  })
}
