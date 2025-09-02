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
  val addedExp = Mux(exp < clzFrac, 0.U, exp - clzFrac)

  val trueExp = Mux(addedExp < bias.U, 0.U, addedExp - bias.U)
  val clippedExp = Mux(trueExp > 255.U, 255.U(8.W), trueExp(7, 0))

  if(wF >= 24) {
    fp := Cat(sign, clippedExp, trueFrac(wF - 2, wF - 2 - 22))
  } else {
    fp := Cat(sign, clippedExp, trueFrac(wF - 2, 0), 0.U((24 - wF).W))
  }
}
