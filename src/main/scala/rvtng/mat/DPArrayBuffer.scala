package rvtng.mat

import rvtng.fpmath.{FPRoundingModule, FPUnpackModule}
import rvtng.utils.RF1R1WRam
import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

// Memory Wrapper For DPArray Buffer
// Read  as entries, Vec(mn, Vec(k, UInt(8.W)))
// Write as entries, Vec(mn, Vec(k, UInt(8.W)))
class DPArrayBufferAB(busWidth: Int, entries: Int, mn: Int, k: Int) extends Module {
  val ptrW = log2Ceil(entries)
  val maxBeat = (mn * k + busWidth - 1) / busWidth
  val beatW = log2Ceil(maxBeat)
  // If busWidth > mn * k, means this is a Scale buffer;
  val we = IO(Input(Vec(mn / maxBeat, Bool())))
  val wAddr = IO(Input(Vec(mn / maxBeat, UInt(ptrW.W))))
  val wBeat = IO(Input(Vec(mn / maxBeat, UInt(beatW.W))))
  val wData = IO(Input(Vec(busWidth, UInt(8.W))))

  // RE is not present
  val rAddr = IO(Input(UInt(ptrW.W)))
  val rData = IO(Output(Vec(mn, Vec(k, UInt(8.W)))))

  // Real rData
  //  val realRData = Wire(UInt((mn * k * 8).W))
  val tiledRData = Wire(Vec(mn, UInt((k * 8).W)))
  rData := tiledRData.asTypeOf(Vec(mn, Vec(k, UInt(8.W))))

  // Backing Storage Buffer
  for (line <- 0 until mn) {
    val extLine = line % (mn / maxBeat)
    val curBeat = line / (mn / maxBeat)
    val backingStorage = Module(new RF1R1WRam(ptrW, k * 8))
    backingStorage.raddr := rAddr
    tiledRData(line) := backingStorage.rdata
    backingStorage.we := we(extLine) && wBeat(extLine) === curBeat.U
    backingStorage.waddr := wAddr(extLine)
    backingStorage.wdata := wData.asUInt((extLine + 1) * k * 8 - 1, extLine * k * 8)
  }
}

// Memory Wrapper For DPArray Buffer
// cPort   as entries, Vec(m, Vec(n, UInt((wE + wF).W)))
// extPort as entries, Vec(m, Vec(n, UInt(32.W))
class DPArrayBufferC(busWidth: Int, entries: Int, m: Int, n: Int, wE: Int, wF: Int) extends Module {
  val ptrW = log2Ceil(entries)
  val maxBeat = (m * n * 4 + busWidth - 1) / busWidth
  val beatW = log2Ceil(maxBeat)
  // If busWidth > mn * k, means this is a Scale buffer;
  val extWe    = IO(Input(Vec(m / maxBeat, Bool())))
  val extWAddr = IO(Input(Vec(m / maxBeat, UInt((ptrW + 1).W))))
  val extWBeat = IO(Input(Vec(m / maxBeat, UInt(beatW.W))))
  val extWData = IO(Input(Vec(busWidth, UInt(8.W))))

  val extRe    = IO(Input(Vec(m / maxBeat, Bool())))
  val extRAddr = IO(Input(Vec(m / maxBeat, UInt((ptrW + 1).W))))
  val extRBeat = IO(Input(Vec(m / maxBeat, UInt(beatW.W))))
  val extRData = IO(Output(Vec(busWidth, UInt(8.W))))

  val cRe      = IO(Input(Bool()))
  val cRAddr   = IO(Input(UInt((ptrW + 1).W)))
  val cRData   = IO(Output(Vec(m, Vec(n, UInt((wE + wF).W)))))

  val cWe      = IO(Input(Bool()))
  val cWAddr   = IO(Input(UInt((ptrW + 1).W)))
  val cWData   = IO(Input(Vec(m, Vec(n, UInt((wE + wF).W)))))

  // Real extData
  // Internal Format -> FP32
  val realExtRData = Wire(Vec(m, UInt((n * (wE + wF)).W)))
  val realExtRDataIFP = realExtRData.asTypeOf(Vec(m * n, UInt((wE + wF).W)))
  val realExtRDataFP32 = VecInit.tabulate(m * n)(i => {
    val cvt = Module(new FPRoundingModule(wE, wF, 0))
    cvt.exp  := realExtRDataIFP(i)(wE + wF - 1, wF).asSInt
    cvt.frac := realExtRDataIFP(i)(     wF - 1,  0)
    cvt.fp
  })
  val s1ExtRBeat = RegNext(extRBeat)
  val realExtRDataFP32View = VecInit.tabulate(m)(i => VecInit.tabulate(n)(j => {
    realExtRDataFP32(i * n + j)
  }))
  val realExtRDataFP32Sel = VecInit.tabulate(m / maxBeat)(i => {
    val sel = VecInit.tabulate(maxBeat)(j => realExtRDataFP32View(i + j * (m / maxBeat)))
    sel(s1ExtRBeat(i))
  })
  extRData := realExtRDataFP32Sel.asTypeOf(extRData)

  // extWData -> FP32
  val extWDataFP32 = extWData.asTypeOf(Vec(m * n / maxBeat, UInt(32.W)))
  // FP32 -> Internal Format
  val realExtWData = VecInit.tabulate(m / maxBeat)(j => VecInit.tabulate(n)(i => {
    val cvt = Module(new FPUnpackModule(wE, wF, 0))
    cvt.fp := extWDataFP32(i + j * n)
    Cat(cvt.exp, cvt.frac).asUInt
  }))

  // Bank Selection Logic
  val extRBank0 = VecInit.tabulate(m / maxBeat)(i => {
    extRe(i) & !extRAddr(i)(ptrW).asBool
  })
  val extRBank1 = VecInit.tabulate(m / maxBeat)(i => {
    extRe(i) &  extRAddr(i)(ptrW).asBool
  })
  val extWBank0 = VecInit.tabulate(m / maxBeat)(i => {
    extWe(i) & !extWAddr(i)(ptrW).asBool
  })
  val extWBank1 = VecInit.tabulate(m / maxBeat)(i => {
    extWe(i) &  extWAddr(i)(ptrW).asBool
  })
  val cRBank0 = cRe & !cRAddr(ptrW).asBool
  val cRBank1 = cRe &  cRAddr(ptrW).asBool
  val cWBank0 = cWe & !cWAddr(ptrW).asBool
  val cWBank1 = cWe &  cWAddr(ptrW).asBool
  val s1ExtRBank0 = RegNext(extRBank0)
  val s1CRBank0 = RegNext(cRBank0)
  chisel3.assert(!(extRBank0.reduceTree(_|_) & cRBank0))
  chisel3.assert(!(extRBank1.reduceTree(_|_) & cRBank1))
  chisel3.assert(!(extWBank0.reduceTree(_|_) & cWBank0))
  chisel3.assert(!(extWBank1.reduceTree(_|_) & cWBank1))

  // Backing Storage Buffer
  for(line <- 0 until m) {
    val extLine = line % (m / maxBeat)
    val curBeat = line / (m / maxBeat)
    val backingStorage0 = Module(new RF1R1WRam(ptrW, n * (wE + wF)))
    val backingStorage1 = Module(new RF1R1WRam(ptrW, n * (wE + wF)))
    backingStorage0.raddr := Mux(extRBank0(extLine), extRAddr(extLine)(ptrW - 1, 0), cRAddr(ptrW - 1, 0))
    backingStorage1.raddr := Mux(extRBank1(extLine), extRAddr(extLine)(ptrW - 1, 0), cRAddr(ptrW - 1, 0))
    backingStorage0.we := (extWBank0(extLine) & curBeat.U === extWBeat(extLine)) | cWBank0
    backingStorage1.we := (extWBank1(extLine) & curBeat.U === extWBeat(extLine)) | cWBank1
    backingStorage0.waddr := Mux(extWBank0(extLine), extWAddr(extLine)(ptrW - 1, 0), cWAddr(ptrW - 1, 0))
    backingStorage1.waddr := Mux(extWBank1(extLine), extWAddr(extLine)(ptrW - 1, 0), cWAddr(ptrW - 1, 0))
    backingStorage0.wdata := Mux(extWBank0(extLine), realExtWData(extLine).asUInt, cWData(line).asUInt)
    backingStorage1.wdata := Mux(extWBank1(extLine), realExtWData(extLine).asUInt, cWData(line).asUInt)
    realExtRData(line) := Mux(s1ExtRBank0(extLine), backingStorage0.rdata, backingStorage1.rdata)
    cRData(line)       := Mux(s1CRBank0,
      backingStorage0.rdata, backingStorage1.rdata).asTypeOf(Vec(n, UInt((wE + wF).W)))
  }
}

object DPArrayBufferAB extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayBufferAB(256, 32, 16, 32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayBufferAB"
    )
  )
}

object DPArrayBufferC extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayBufferC(256, 32, 16, 32, 9, 25),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayBufferC"
    )
  )
}
