package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class FPRundingModule(wE: Int, wF: Int, bias: Int) extends Module {
  val exp = IO(Input(UInt(wE.W)))
  val frac = IO(Input(UInt(wF.W)))
  val fp = IO(Output(UInt(32.W)))

  val sign = frac(wF-1)
  val fracAbs = Mux(sign.asBool, -frac, frac)

  val clzFrac = CLZ(fracAbs)
  val trueFrac = fracAbs << clzFrac
  val addedExp = converter.clipSub(exp.zext, (bias.U +& clzFrac).zext, 255.S, 0.S)

  if(wF >= 24) {
    fp := Cat(sign, addedExp(7, 0), trueFrac(wF - 2, wF - 2 - 22))
  } else {
    fp := Cat(sign, addedExp(7, 0), trueFrac(wF - 2, 0), 0.U((24 - wF).W))
  }
}
