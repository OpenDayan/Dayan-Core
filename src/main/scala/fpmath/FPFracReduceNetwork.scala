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
  val latency = 1 + log2Ceil(n)/3

  // Input a group of Fraction and their Exponent Difference
  // Reduce to a single Fraction
  val frac_in  = IO(Input(Vec(n, SInt(wF.W))))
  val diff     = IO(Input(Vec(n, UInt())))

  // Stage 1: Shift to position
  val next_s1 = VecInit.tabulate(n)(i => {
    // S1.(wI - 1)
    val extendedInput = Cat(frac_in(i), 0.U((wI - wF).W)).asSInt
    val shifter = converter.shiftRightSigned(extendedInput, diff(i))
    val shiftedValue = Wire(SInt(wI.W))
    shiftedValue := Mux(enableStickBit.B, shifter._1.asUInt | shifter._2.asUInt, shifter._1.asUInt).asSInt
    shiftedValue
  })

  // TODO: Optimize me
  def buildAddTree(input: Vec[SInt], depth: Int = 0): SInt = {
    val layerRaw = {
      if(input.length == 1) {
        input(0)
      } else {
        val l = buildAddTree(VecInit.tabulate((input.length+1)/2)(i => input(i)) ,depth+1)
        val r = buildAddTree(VecInit.tabulate(input.length/2)(i => input((input.length+1)/2 + i)) ,depth+1)
        l +& r
      }
    }
    if(depth % 3 == 0) {
      RegNext(layerRaw)
    } else {
      layerRaw
    }
  }

  val frac_out = IO(Output(SInt((wI + log2Ceil(n)).W)))
  frac_out := buildAddTree(next_s1, 0)

}
