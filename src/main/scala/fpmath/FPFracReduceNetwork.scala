package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.pipe

// wF: Width of Fraction Input
// wI: Width of Fraction Shifter

class FPFracReduceNetwork(
  n: Int, wF: Int, wI: Int, enableStickBit: Boolean = true
) extends Module {
  require(n >= 1, "N should larger than 1")
  require(wI >= wF, "Shifter Should Longer than Fraction")
  val latency = 2

  // Input a group of Fraction and their Exponent Difference
  // Reduce to a single Fraction
  val frac_in  = IO(Input(Vec(n, SInt(wF.W))))
  val diff     = IO(Input(Vec(n, UInt())))

  // Stage 1: Shift to position
  val next_s1 = VecInit.tabulate(n)(i => {
    val extendedInput = Cat(frac_in(i), 0.U((wI - wF).W)).asSInt
    val shifter = converter.shiftRightSigned(extendedInput, diff(i))
    val shiftedValue = Wire(UInt(wI.W))
    shiftedValue := Mux(enableStickBit.B, shifter._1.asUInt | shifter._2.asUInt, shifter._1.asUInt).asSInt
    shiftedValue
  })
  val s1 = RegNext(next_s1)

  // Stage 2: Reduce
  // TODO: Optimize me
  val next_s2 = s1.reduceTree((l, r) => {
    l +& r
  })
  require(next_s2.getWidth == wI + log2Up(n), "Output width should equal to expanded reduce result")

  val frac_out = IO(Output(SInt((wI+log2Up(n)).W)))
  frac_out := RegNext(next_s2)

}
