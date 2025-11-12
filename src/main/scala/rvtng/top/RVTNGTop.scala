package rvtng.top

import chisel3._
import chisel3.util._
import rvtng.uram.Adv2R1WURAM
import rvtng.vec.{VecParams, VecTop}
import rvtng.mat.{MatParams, MatTop}
import rvtng.dma.{DMAParams, DMATop}
import _root_.circt.stage.ChiselStage
import rvtng.bus.{AXI4Bundle, AXI4BundleParameters, DPIAXI4}

// Full 8-TFlops/GHz/Core Implementation
class RVTNGTop(matParams: MatParams, vecParams: VecParams, dmaParams: DMAParams, name: String = "RVTNGTop") extends Module {
  // 实例化 MAT、Vec、DMA、URAM，并完成顶层指令分发、Scoreboard 实现。

  private val axi4Parms = AXI4BundleParameters(
    addressBits = dmaParams.addressBits, dataBits = dmaParams.dataBits, idBits = dmaParams.sourceBits
  )

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val axi4 = IO(new AXI4Bundle(axi4Parms))

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
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = dmaParams.sourceBits, dataWidth = 64
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
  dma.instReq <> disp.dmaReq
  dma.instResp <> disp.dmaResp
  dma.sbComp <> sb.sbComp(2)
  dma.uramRReq <> uram.r1req_i(1)
  dma.uramRResp <> uram.r1resp_o(1)
  dma.uramWReq <> uram.wreq_i(2)
  dma.uramWResp <> uram.wresp_o(2)
  dma.axi4 <> axi4
  uram.wdata_i(2) := dma.uramWData

  // Scoreboard Connection
  sb.instReq <> disp.sbReq
  sb.instResp <> disp.sbResp
}

class RVTNGTestTop() extends Module {
  private val matParams = MatParams()
  private val vecParams = VecParams()
  private val dmaParams = DMAParams()
  private val core = Module(new RVTNGTop(matParams, vecParams, dmaParams))
  private val axi4Parms = AXI4BundleParameters(
    addressBits = dmaParams.addressBits, dataBits = dmaParams.dataBits, idBits = dmaParams.sourceBits
  )

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  // DPI Instance
  private val axi4 = Module(new DPIAXI4(axi4Parms, 0)).axi
  core.instReq <> instReq
  core.instResp <> instResp
  core.axi4 <> axi4
}

object RVTNGTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new RVTNGTestTop(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/RVTNGTestTop"
    )
  )
}
