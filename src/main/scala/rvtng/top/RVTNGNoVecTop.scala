package rvtng.top

import chisel3._
import chisel3.util._
import rvtng.uram.Adv2R1WURAM
import rvtng.vec.{VecParams, VecTop}
//import rvtng.mat.{MatParams, MatTop}
import rvtng.neomat.{MatParams, MatTop}
import rvtng.dma.{DMAParams, DMATop}
import _root_.circt.stage.ChiselStage
import rvtng.bus._

// Full 8-TFlops/GHz/Core Implementation
class RVTNGNoVecTop(matParams: MatParams, vecParams: VecParams, dmaParams: DMAParams, name: String = "RVTNGTop") extends Module {
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

  // Tilelink UH To URAM
  private val tlParms = TLBundleParameters(
    addressBits = dmaParams.addressBits, dataBits = 256, sourceBits = dmaParams.sourceBits, sinkBits = 1,
    sizeBits = log2Ceil(256 / 8) , hasBCE = false
  )
  val tl2u = Module(new TilelinkUHToURAM(tlParms, 24, uramPortPerChannel))
  val tl = IO(Flipped(new TLBundle(tlParms)))
  tl <> tl2u.tl

  // Scoreboard
  private val sb = Module(new RVTNGScoreBoard(8, 3))

  // URAM Instance
  // URAM RP0: MAT.B,  TLK.L
  // URAM RP1: MAT.AC, LSU.L
  // URAM WP0: MAT.C,  LSU.S, TLK.S [LSU, LSU, TLK, NIL, NIL, NIL, MAT, MAT]
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 2, R1Port = 2, W0Port = 3, GroupCount = uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = dmaParams.sourceBits, dataWidth = 64
  ))

  // SubModule Instance
  // private val vec = Module(new VecTop(vecParams))
  private val mat = Module(new MatTop(matParams)).io
  private val dma = Module(new DMATop(dmaParams, true))

  // Instruction Dispatch logic
  private val disp = Module(new RVTNGInstDispatch)
  disp.instReq  <> instReq
  disp.instResp <> instResp

  // TL Slave Connection
  for(i <- 0 until uramChannel * uramPortPerChannel) {
    if(i < 2 * uramPortPerChannel) {
      // Empty
      uram.wreq_i(2)(i).valid := 0.B
      uram.wreq_i(2)(i).bits := DontCare
      uram.wdata_i(2)(i) := DontCare
      uram.r0req_i(1)(i).valid := 0.B
      uram.r0req_i(1)(i).bits := DontCare
    } else if(i < 3 * uramPortPerChannel) {
      uram.wreq_i(2)(i) <> tl2u.uramWReq(i - 2 * uramPortPerChannel)
      uram.wdata_i(2)(i) := tl2u.uramWData(i - 2 * uramPortPerChannel)
      uram.r0req_i(1)(i) <> tl2u.uramRReq(i - 2 * uramPortPerChannel)
    } else {
      // Empty
      uram.wreq_i(2)(i).valid := 0.B
      uram.wreq_i(2)(i).bits := DontCare
      uram.wdata_i(2)(i) := DontCare
      uram.r0req_i(1)(i).valid := 0.B
      uram.r0req_i(1)(i).bits := DontCare
    }
  }
  for(i <- 0 until uramChannel) {
    if(i < 2) {
      uram.wresp_o(2)(i).ready := 1.B
      uram.r0resp_o(1)(i).ready := 1.B
    } else if(i < 3) {
      tl2u.uramWResp(i - 2) <> uram.wresp_o(2)(i)
      tl2u.uramRResp(i - 2) <> uram.r0resp_o(1)(i)
    } else {
      uram.wresp_o(2)(i).ready := 1.B
      uram.r0resp_o(1)(i).ready := 1.B
    }
  }

  // Mat Connection
  mat.instReq <> disp.matReq
  mat.instResp <> disp.matResp
  mat.sbComp <> sb.sbComp(0)
  mat.uramRReq0 <> uram.r0req_i(0)
  mat.uramRResp0 <> uram.r0resp_o(0)
  mat.uramRReq1 <> uram.r1req_i(0)
  mat.uramRResp1 <> uram.r1resp_o(0)
//  mat.uramWReq <> uram.wreq_i(0)
//  mat.uramWResp <> uram.wresp_o(0)
//  uram.wdata_i(0) := mat.uramWData

  for(i <- 0 until matParams.busChannel * uramPortPerChannel) {
    if(i < matParams.busWriteChannel * uramPortPerChannel) {
      // Avoid DMA Write Port
      uram.wreq_i(0)( (matParams.busChannel - matParams.busWriteChannel) * uramPortPerChannel + i) <> mat.uramWReq(i)
      uram.wdata_i(0)((matParams.busChannel - matParams.busWriteChannel) * uramPortPerChannel + i) := mat.uramWData(i)
    } else {
      uram.wreq_i(0)(i - matParams.busWriteChannel * uramPortPerChannel).valid := 0.B
      uram.wreq_i(0)(i - matParams.busWriteChannel * uramPortPerChannel).bits := DontCare
      uram.wdata_i(0)(i - matParams.busWriteChannel * uramPortPerChannel) := DontCare
    }
  }
  for(i <- 0 until matParams.busChannel) {
    if(i < matParams.busWriteChannel) {
      mat.uramWResp(i) <> uram.wresp_o(0)(matParams.busChannel - matParams.busWriteChannel + i)
    } else {
      uram.wresp_o(0)(i - matParams.busWriteChannel).ready := 1.B
    }
  }

  // Vec Connection
  disp.vecReq.ready := 1.B
  // vec.instReq <> disp.vecReq
  disp.vecResp.valid := 0.B
  disp.vecResp.bits := DontCare
  sb.sbComp(1).valid := 0.B
  sb.sbComp(1).bits := DontCare
  // vec.instResp <> disp.vecResp
  // vec.sbComp <> sb.sbComp(1)
  // vec.uramRReq <> uram.r0req_i(1)
  // vec.uramRResp <> uram.r0resp_o(1)
  // vec.uramWReq <> uram.wreq_i(1)
  // vec.uramWResp <> uram.wresp_o(1)
  // uram.wdata_i(1) := vec.uramWData

  // DMA Connection
  dma.instReq <> disp.dmaReq
  dma.instResp <> disp.dmaResp
  dma.sbComp <> sb.sbComp(2)
  dma.uramRReq <> uram.r1req_i(1)
  dma.uramRResp <> uram.r1resp_o(1)
  // dma.uramWReq <> uram.wreq_i(2)
  // dma.uramWResp <> uram.wresp_o(2)
  // uram.wdata_i(2) := dma.uramWData
  dma.uramWReq <> uram.wreq_i(1)
  dma.uramWResp <> uram.wresp_o(1)
  uram.wdata_i(1) := dma.uramWData
  dma.axi4 <> axi4

  // Scoreboard Connection
  sb.instReq <> disp.sbReq
  sb.instResp <> disp.sbResp
}

object RVTNGNoVecTop extends App {
  private val matParams = MatParams()
  private val vecParams = VecParams()
  private val dmaParams = DMAParams()
  ChiselStage.emitSystemVerilog(
    new RVTNGNoVecTop(matParams, vecParams, dmaParams),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/RVTNGNoVecTop"
    )
  )
}
