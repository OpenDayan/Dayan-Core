package rvtng.top

import chisel3._
import chisel3.util._
import rvtng.uram.Adv2R1WURAM
import rvtng.vec.{VecParams, VecTop}
import rvtng.mat.{MatParams, MatTop}
import rvtng.dma.{DMAParams, DMATop}

// Full 8-TFlops/GHz/Core Implementation
class RVTNGTop(name: String = "RVTNGTop") extends Module {
  // 实例化 MAT、Vec、DMA、URAM，并完成顶层指令分发、Scoreboard 实现。

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))

  private val matParams = MatParams()
  private val vecParams = VecParams()
  private val dmaParams = DMAParams()

  override def desiredName: String = name
  private val uramPort = (vecParams.laneWidth * 32 / 2) / 64
  private val uramPortPerChannel = vecParams.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // Scoreboard
  private val sb = Module(new RVTNGScoreBoard(8, 3))

  // URAM Instance
  // URAM RP0: MAT.B,  VEC.L
  // URAM RP1: MAT.AC, LSU.L
  // URAM WP0: MAT.C,  VEC.S, LSU.S
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 2, R1Port = 2, W0Port = 3, GroupCount = vecParams.uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = vecParams.sourceBits, dataWidth = 64
  ))

  // SubModule Instance
  private val vec = Module(new VecTop(vecParams))
  private val mat = Module(new MatTop(matParams))
  private val dma = Module(new DMATop(dmaParams, true))

  // Instruction Dispatch logic
  private val disp = Module(new RVTNGInstDispatch)
  disp.instReq  <> instReq
  disp.instResp <> instResp

  // Mat Connection
  mat.instReq <> disp.matReq
  mat.instResp <> disp.matResp
  mat.sbComp <> sb.sbComp(0)
  mat.uramRReq0 <> uram.r0req_i(0)
  mat.uramRResp0 <> uram.r0resp_o(0)
  mat.uramRReq1 <> uram.r1req_i(0)
  mat.uramRResp1 <> uram.r1resp_o(0)
  mat.uramWReq <> uram.wreq_i(0)
  mat.uramWResp <> uram.wresp_o(0)
  uram.wdata_i(0) := mat.uramWData

  // Vec Connection
  vec.instReq <> disp.vecReq
  vec.instResp <> disp.vecResp
  vec.sbComp <> sb.sbComp(1)
  vec.uramRReq <> uram.r0req_i(1)
  vec.uramRResp <> uram.r0resp_o(1)
  vec.uramWReq <> uram.wreq_i(1)
  vec.uramWResp <> uram.wresp_o(1)
  uram.wdata_i(1) := vec.uramWData

  // DMA Connection
}
