package rvtng.top

import chisel3._
import chisel3.util._
import rvtng.bus.{TLBundle, TLBundleParameters, TilelinkUHToURAM}
import rvtng.uram.{Adv2R1WURAM, Neo2R1WURAM}
import rvtng.vec.{VecParams, VecTop}
//import rvtng.mat.{MatParams, MatTop}
import rvtng.neomat.{MatParams, MatTop}
import rvtng.dma.{DMAParams, DMATop}
import _root_.circt.stage.ChiselStage
import rvtng.bus.{AXI4Bundle, AXI4BundleParameters, DPIAXI4}
import rvtng.vpu._

// Full 8-TFlops/GHz/Core Implementation
class RVTNGTop(matParams: MatParams, vecParams: VecParams, dmaParams: DMAParams, vpuParams: VpuParams, name: String = "RVTNGTop") extends Module {
  // 实例化 MAT、Vec、DMA、URAM，并完成顶层指令分发、Scoreboard 实现。

  private val axi4Parms = AXI4BundleParameters(
    addressBits = dmaParams.addressBits, dataBits = dmaParams.dataBits, idBits = dmaParams.sourceBits
  )

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val axi4 = IO(new AXI4Bundle(axi4Parms))

  override def desiredName: String = name
  private val uramPortPerChannel = vecParams.uramPortPerChannel

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
  // URAM RP0: MAT.B,  VEC.L  TL.L
  private val MATRP0PORT = matParams.busChannel * uramPortPerChannel
  private val VECRP0PORT = (vpuParams.laneWidth * 32 / 2) / 64
  private val TLLRP0PORT = tlParms.dataBits / 64
  // URAM RP1: MAT.AC, LSU.L
  private val MATRP1PORT = matParams.busChannel * uramPortPerChannel
  private val LSURP1PORT = dmaParams.dataBits / 64
  // URAM WP0: MAT.C,  VEC.S, LSU.S  TL.S
  private val MATWP0PORT = matParams.busWriteChannel * uramPortPerChannel
  private val VECWP0PORT = (vpuParams.laneWidth * 32 / 2) / 64
  private val LSUWP0PORT = dmaParams.dataBits / 64
  private val TLSWP0PORT  = tlParms.dataBits / 64

  private val MATRP0CHAN = MATRP0PORT / uramPortPerChannel
  private val VECRP0CHAN = VECRP0PORT / uramPortPerChannel
  private val TLLRP0CHAN = TLLRP0PORT / uramPortPerChannel
  private val MATRP1CHAN = MATRP1PORT / uramPortPerChannel
  private val LSURP1CHAN = LSURP1PORT / uramPortPerChannel
  private val MATWP0CHAN = MATWP0PORT / uramPortPerChannel
  private val VECWP0CHAN = VECWP0PORT / uramPortPerChannel
  private val LSUWP0CHAN = LSUWP0PORT / uramPortPerChannel
  private val TLSWP0CHAN = TLSWP0PORT / uramPortPerChannel

  private val uram = Module(new Neo2R1WURAM(
    r0Port = MATRP0PORT + VECRP0PORT + TLLRP0PORT,
    r1Port = MATRP1PORT + LSURP1PORT,
    w0Port = MATWP0PORT + VECWP0PORT + LSUWP0PORT + TLSWP0PORT,
    portsPerChannel = uramPortPerChannel,
    fifoSize = 16, bank = 32, addrWidth = 24, idWidth = dmaParams.sourceBits, dataWidth = 64)).io

  // SubModule Instance
  // private val vec = Module(new VecTop(vecParams))
  private val vpu = Module(new VPUTop[Float](vpuParams))
  private val mat = Module(new MatTop(matParams)).io
  private val dma = Module(new DMATop(dmaParams, true))

  // Instruction Dispatch logic
  private val disp = Module(new RVTNGInstDispatch)
  disp.instReq  <> instReq
  disp.instResp <> instResp

  // Mat Connection
  mat.instReq <> disp.matReq
  mat.instResp <> disp.matResp
  mat.sbComp <> sb.sbComp(0)
  mat.uramRReq0  <> uram.r0Req.slice(0, MATRP0PORT)
  mat.uramRResp0 <> uram.r0Resp.slice(0, MATRP0CHAN)
  mat.uramRReq1  <> uram.r1Req.slice(0, MATRP1PORT)
  mat.uramRResp1 <> uram.r1Resp.slice(0, MATRP1CHAN)
  mat.uramWReq  <> uram.w0Req.slice(0, MATWP0PORT)
  mat.uramWResp <> uram.w0Resp.slice(0, MATWP0CHAN)
  mat.uramWData <> uram.w0Data.slice(0, MATWP0PORT)

  // Vec Connection
  disp.vecReq.ready := 1.B
  disp.vecResp.valid := 0.B
  disp.vecResp.bits := DontCare
  vpu.instReq <> disp.vpuReq
  vpu.instResp <> disp.vpuResp
  vpu.sbComp <> sb.sbComp(1)
  vpu.uramRReq  <> uram.r0Req.slice(MATRP0PORT, MATRP0PORT + VECRP0PORT)
  vpu.uramRResp <> uram.r0Resp.slice(MATRP0CHAN, MATRP0CHAN + VECRP0CHAN)
  vpu.uramWReq  <> uram.w0Req.slice(MATWP0PORT, MATWP0PORT + VECWP0PORT)
  vpu.uramWResp <> uram.w0Resp.slice(MATWP0CHAN, MATWP0CHAN + VECWP0CHAN)
  vpu.uramWData <> uram.w0Data.slice(MATWP0PORT, MATWP0PORT + VECWP0PORT)

  // DMA Connection
  dma.instReq <> disp.dmaReq
  dma.instResp <> disp.dmaResp
  dma.sbComp <> sb.sbComp(2)
  dma.uramRReq  <> uram.r1Req.slice(MATRP1PORT, MATRP1PORT + LSURP1PORT)
  dma.uramRResp <> uram.r1Resp.slice(MATRP1CHAN, MATRP1CHAN + LSURP1CHAN)
  dma.uramWReq  <> uram.w0Req.slice(MATWP0PORT + VECWP0PORT, MATWP0PORT + VECWP0PORT + LSUWP0PORT)
  dma.uramWResp <> uram.w0Resp.slice(MATWP0CHAN + VECWP0CHAN, MATWP0CHAN + VECWP0CHAN + LSUWP0CHAN)
  dma.uramWData <> uram.w0Data.slice(MATWP0PORT + VECWP0PORT, MATWP0PORT + VECWP0PORT + LSUWP0PORT)
  dma.axi4 <> axi4

  // TL Slave Connection
  tl2u.uramRReq  <> uram.r0Req.slice(MATRP0PORT + VECRP0PORT, MATRP0PORT + VECRP0PORT + TLLRP0PORT)
  tl2u.uramRResp <> uram.r0Resp.slice(MATRP0CHAN + VECRP0CHAN, MATRP0CHAN + VECRP0CHAN + TLLRP0CHAN)
  tl2u.uramWReq <> uram.w0Req.slice(MATWP0PORT + VECWP0PORT + LSUWP0PORT, MATWP0PORT + VECWP0PORT + LSUWP0PORT + TLSWP0PORT)
  tl2u.uramWResp <> uram.w0Resp.slice(MATWP0CHAN + VECWP0CHAN + LSUWP0CHAN, MATWP0CHAN + VECWP0CHAN + LSUWP0CHAN + TLSWP0CHAN)
  tl2u.uramWData <> uram.w0Data.slice(MATWP0PORT + VECWP0PORT + LSUWP0PORT, MATWP0PORT + VECWP0PORT + LSUWP0PORT + TLSWP0PORT)

  // Scoreboard Connection
  sb.instReq <> disp.sbReq
  sb.instResp <> disp.sbResp
}

class RVTNGTestTop() extends Module {
  private val matParams = MatParams(cM = 8, cN = 8, cK = 32)
  private val vecParams = VecParams()
  private val dmaParams = DMAParams()
  private val vpuParams = VpuParams()
  private val core = Module(new RVTNGTop(matParams, vecParams, dmaParams, vpuParams))
  private val axi4Parms = AXI4BundleParameters(
    addressBits = dmaParams.addressBits, dataBits = dmaParams.dataBits, idBits = dmaParams.sourceBits
  )
  private val tlParms = TLBundleParameters(
    addressBits = dmaParams.addressBits, dataBits = 256, sourceBits = dmaParams.sourceBits, sinkBits = 1,
    sizeBits = log2Ceil(256 / 8) , hasBCE = false
  )

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val tl = IO(Flipped(new TLBundle(tlParms)))
  // DPI Instance
  private val axi4 = Module(new DPIAXI4(axi4Parms, 0)).axi
  core.instReq <> instReq
  core.instResp <> instResp
  core.axi4 <> axi4
  core.tl <> tl
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
