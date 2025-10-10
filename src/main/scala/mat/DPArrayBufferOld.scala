package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import fpmath.{FPUnpackModule, FPRoundingModule}
import utils.RF1R1WRamBase

// Memory Wrapper For DPArray Buffer
// Read  as entries, Vec(mn, Vec(k, UInt(8.W)))
// Write as entries, Vec(busWidth, UInt(8.W))
class DPArrayBufferABOld(busWidth: Int, entries: Int, mn: Int, k: Int) extends Module {
  val ptrW = log2Ceil(entries)
  val maxBeat = (mn * k) / busWidth
  val beatW = log2Ceil(maxBeat)
  require(maxBeat == 0 | (busWidth * maxBeat == mn * k))
  // If busWidth > mn * k, means this is a Scale buffer;
  val we    = IO(Input(Bool()))
  val wAddr = IO(Input(UInt(ptrW.W)))
  val wBeat = IO(Input(UInt(beatW.W)))
  val wData = IO(Input(Vec(busWidth, UInt(8.W))))

  // RE is not present
  val rAddr = IO(Input(UInt(ptrW.W)))
  val rData = IO(Output(Vec(mn, Vec(k, UInt(8.W)))))

  // Real rData
  val realRData = Wire(UInt((mn * k * 8).W))
  rData := realRData.asTypeOf(Vec(mn, Vec(k, UInt(8.W))))

  // Backing Storage Buffer
  if(maxBeat <= 1) {
    val backingStorage = Module(new RF1R1WRamBase(ptrW, mn * k * 8))
    backingStorage.io.clk := clock
    backingStorage.io.reset := reset
    backingStorage.io.raddr := rAddr
    realRData := backingStorage.io.rdata
    backingStorage.io.we := we
    backingStorage.io.waddr := wAddr
    backingStorage.io.wdata := wData.asUInt(mn * k * 8 - 1, 0)
  } else {
    val tiledRData = Wire(Vec(maxBeat, UInt((busWidth * 8).W)))
    for(i <- 0 until maxBeat) {
      val backingStorage = Module(new RF1R1WRamBase(ptrW, busWidth * 8))
      backingStorage.io.clk := clock
      backingStorage.io.reset := reset
      backingStorage.io.raddr := rAddr
      tiledRData(i) := backingStorage.io.rdata
      backingStorage.io.we := we & (i.U === wBeat)
      backingStorage.io.waddr := wAddr
      backingStorage.io.wdata := wData.asUInt
    }
    realRData := tiledRData.asUInt
  }

}

// Memory Wrapper For DPArray Buffer
// Read  as entries, Vec(m, Vec(n, UInt(8.W)))
// Write as entries, Vec(busWidth, UInt(8.W))
class DPArrayBufferCOld(busWidth: Int, entries: Int, m: Int, n: Int, wE: Int, wF: Int) extends Module {
  val ptrW = log2Ceil(entries)
  val maxBeat = (m * n * 4) / busWidth
  val beatW = log2Ceil(maxBeat)
  require(busWidth * maxBeat == (m * n * 4))
  // If busWidth > mn * k, means this is a Scale buffer;
  val extWe    = IO(Input(Bool()))
  val extWAddr = IO(Input(UInt((ptrW + 1).W)))
  val extWBeat = IO(Input(UInt(beatW.W)))
  val extWData = IO(Input(Vec(busWidth, UInt(8.W))))

  val extRe    = IO(Input(Bool()))
  val extRAddr = IO(Input(UInt((ptrW + 1).W)))
  val extRBeat = IO(Input(UInt(beatW.W)))
  val extRData = IO(Output(Vec(busWidth, UInt(8.W))))

  val cRe      = IO(Input(Bool()))
  val cRAddr   = IO(Input(UInt((ptrW + 1).W)))
  val cRData   = IO(Output(Vec(m, Vec(n, UInt((wE + wF).W)))))

  val cWe      = IO(Input(Bool()))
  val cWAddr   = IO(Input(UInt((ptrW + 1).W)))
  val cWData   = IO(Input(Vec(m, Vec(n, UInt((wE + wF).W)))))

  // Real cData
  val realCRData = Wire(Vec(maxBeat, UInt(((m * n / maxBeat) * (wE + wF)).W)))
  val realCWData = Wire(UInt((m * n * (wE + wF)).W))
  cRData := realCRData.asTypeOf(Vec(m, Vec(n, UInt((wE + wF).W))))
  realCWData := cWData.asUInt

  // Real extData
  // Internal Format -> FP32
  val realExtRData = Wire(Vec(maxBeat, UInt(((m * n / maxBeat) * (wE + wF)).W)))
  val realExtRDataIFP = realExtRData.asTypeOf(Vec(m * n, UInt((wE + wF).W)))
  val realExtRDataFP32 = VecInit.tabulate(m * n)(i => {
    val cvt = Module(new FPRoundingModule(wE, wF, 0))
    cvt.exp  := realExtRDataIFP(i)(wE + wF - 1, wF).asSInt
    cvt.frac := realExtRDataIFP(i)(     wF - 1,  0)
    cvt.fp
  })
  val s1ExtRBeat = RegNext(extRBeat)
  extRData := realExtRDataFP32.asTypeOf(Vec(maxBeat, Vec(busWidth, UInt(8.W))))(s1ExtRBeat)

  // extWData -> FP32
  val extWDataFP32 = extWData.asTypeOf(Vec(m * n / maxBeat, UInt(32.W)))
  // FP32 -> Internal Format
  val realExtWData = VecInit.tabulate(m * n / maxBeat)(i => {
    val cvt = Module(new FPUnpackModule(wE, wF, 0))
    cvt.fp := extWDataFP32(i)
    Cat(cvt.exp, cvt.frac).asUInt
  })

  // Bank Selection Logic
  val extRBank0 = extRe & !extRAddr(ptrW).asBool
  val extRBank1 = extRe &  extRAddr(ptrW).asBool
  val extWBank0 = extWe & !extWAddr(ptrW).asBool
  val extWBank1 = extWe &  extWAddr(ptrW).asBool
  val cRBank0 = cRe & !cRAddr(ptrW).asBool
  val cRBank1 = cRe &  cRAddr(ptrW).asBool
  val cWBank0 = cWe & !cWAddr(ptrW).asBool
  val cWBank1 = cWe &  cWAddr(ptrW).asBool
  val s1ExtRBank0 = RegNext(extRBank0)
  val s1CRBank0 = RegNext(cRBank0)
  chisel3.assert(!(extRBank0 & cRBank0))
  chisel3.assert(!(extRBank1 & cRBank1))
  chisel3.assert(!(extWBank0 & cWBank0))
  chisel3.assert(!(extWBank1 & cWBank1))

  // Backing Storage Buffer
  for(i <- 0 until maxBeat) {
    val backingStorage0 = Module(new RF1R1WRamBase(ptrW, (m * n) / maxBeat * (wE + wF)))
    val backingStorage1 = Module(new RF1R1WRamBase(ptrW, (m * n) / maxBeat * (wE + wF)))
    backingStorage0.io.clk := clock
    backingStorage1.io.clk := clock
    backingStorage0.io.reset := reset
    backingStorage1.io.reset := reset
    backingStorage0.io.raddr := Mux(extRBank0, extRAddr(ptrW - 1, 0), cRAddr(ptrW - 1, 0))
    backingStorage1.io.raddr := Mux(extRBank1, extRAddr(ptrW - 1, 0), cRAddr(ptrW - 1, 0))
    backingStorage0.io.we := (extWBank0 & i.U === extWBeat) | cWBank0
    backingStorage1.io.we := (extWBank1 & i.U === extWBeat) | cWBank1
    backingStorage0.io.waddr := Mux(extWBank0, extWAddr(ptrW - 1, 0), cWAddr(ptrW - 1, 0))
    backingStorage1.io.waddr := Mux(extWBank1, extWAddr(ptrW - 1, 0), cWAddr(ptrW - 1, 0))
    backingStorage0.io.wdata := Mux(extWBank0, realExtWData.asUInt,
      cWData.asTypeOf(Vec(maxBeat, UInt((m * n / maxBeat * (wE + wF)).W)))(i))
    backingStorage1.io.wdata := Mux(extWBank1, realExtWData.asUInt,
      cWData.asTypeOf(Vec(maxBeat, UInt((m * n / maxBeat * (wE + wF)).W)))(i))
    realExtRData(i) := Mux(s1ExtRBank0, backingStorage0.io.rdata, backingStorage1.io.rdata)
    realCRData(i)   := Mux(s1CRBank0, backingStorage0.io.rdata, backingStorage1.io.rdata)
  }
}

object DPArrayBufferABOld extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayBufferABOld(256, 32, 16, 32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayBufferAB"
    )
  )
}

object DPArrayBufferCOld extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayBufferCOld(256, 32, 16, 32, 9, 25),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayBufferC"
    )
  )
}
