package RVTNG.fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import mat.DPArrayTop

class FPUnpackModule(wE: Int, wF: Int, bias: Int) extends Module {
  val fp = IO(Input(UInt(32.W)))
  val exp = IO(Output(SInt(wE.W)))
  val frac = IO(Output(UInt(wF.W)))
  require(wE == 9)

  val iSgn = fp(31)
  val iExp = fp(30, 23)
  val iFrac = fp(22, 0) // .23b

  exp := ((iExp +& 1.U) - 127.U).asSInt
  val extFrac = Cat(0.U(1.W), Mux(iExp === 0.U, 0.U(1.W), 1.U(1.W)), iFrac) // s1.23b == 25b
  val sFrac = Mux(iSgn.asBool, -extFrac, extFrac).asSInt

  if(wF <= 25) {
    frac := sFrac(24, 25 - wF)
  } else {
    frac := Cat(sFrac, 0.S).asSInt
  }
}

class TestFPUnpackPack extends Module {
  val fpIn = IO(Input(UInt(32.W)))
  val fpOut = IO(Output(UInt(32.W)))
  val unpack = Module(new FPUnpackModule(9, 25, 0))
  val pack = Module(new FPRoundingModule(9, 25, 0))
  unpack.fp := fpIn
  pack.exp := unpack.exp
  pack.frac := unpack.frac
  fpOut := pack.fp
}

object TestFPUnpackPack extends App {
  ChiselStage.emitSystemVerilog(
    new TestFPUnpackPack(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/TestFPUnpackPack"
    )
  )
}



