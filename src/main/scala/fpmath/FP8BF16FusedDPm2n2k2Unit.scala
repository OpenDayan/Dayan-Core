package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Designed for FPGA Usage
// wE: width of Exponent
// wF: width of Fraction (Signed)
// wI: width of Inner Fraction Adder for FP8
class FP8BF16FusedDPm2n2k2Unit(wE: Int, wF: Int = 17, wI: Int = 17, enableStickBit: Boolean = false) extends Module {
  require(wF >= 17, "BF16 Should be full precise")
  require(wI >= wF, "Inner Fraction Adder should support BF16 Data-path")
  val a_in = IO(Input(UInt(32.W)))            // Packed m2k2-FP8 or m2k1-BF16 (m,k)
  val b_in = IO(Input(UInt(32.W)))            // Packed n2k2-FP8 or m2k1-BF16 (n,k)
  val bf16 = IO(Input(Bool()))                // Compute BF16 * BF16 Instead of FP8 * FP8

  // Control Flow
  val bf16_s1 = RegNext(bf16)
  val bf16_s2 = RegNext(bf16_s1)
  val bf16_s3 = RegNext(bf16_s2)

  val c_exp = IO(Output(Vec(2, Vec(2, UInt(wE.W)))))  // Packed m2n2-Exp Output, exp + 254 represent
  val c_frac = IO(Output(Vec(2, Vec(2, SInt(wF.W))))) // Packed m2n2-Frac

  val a_fp8 = VecInit.tabulate(2,2)((j,i) => converter.func_unpack_e4m3(a_in, i + j * 2))
  val b_fp8 = VecInit.tabulate(2,2)((j,i) => converter.func_unpack_e4m3(b_in, i + j * 2))
  val a_bf16 = VecInit.tabulate(2)(i => converter.func_unpack_bf16(a_in,i))
  val b_bf16 = VecInit.tabulate(2)(i => converter.func_unpack_bf16(b_in,i))

  /* Fraction Computation Logic */
  // Prepare Multiplicand
  // (m/n, k)
  // BF16:
  //   Muler0: (a(1,0), 0.U(8.W), a(0,0)) * b(0,0) = (c(1,0), c(0,0))
  //   Muler1: (a(1,0), 0.U(8.W), a(0,0)) * b(1,0) = (c(1,1), c(0,1)))
  // FP8:
  //   Muler0: (a(1,0), 0.U(12.W), a(0,0)) * (b(1,0), 0.U(4.W), b(0,0)) = (c0(1,1), c0(1,0), c0(0,1), c0(0,0))
  //   Muler1: (a(1,1), 0.U(12.W), a(0,1)) * (b(1,1), 0.U(4.W), b(0,1)) = (c1(1,1), c1(1,0), c1(0,1), c1(0,0))
  val muler_a_s1 = Reg(Vec(2,UInt(27.W)))
  val muler_b_s1 = Reg(Vec(2,UInt(18.W)))
  when(bf16) {
    muler_a_s1(0) := Cat(0.U, a_bf16(1).m, 0.U(8.W), a_bf16(0).m)
    muler_a_s1(1) := Cat(0.U, a_bf16(1).m, 0.U(8.W), a_bf16(0).m)
    muler_b_s1(0) := Cat(0.U, b_bf16(0).m)
    muler_b_s1(1) := Cat(0.U, b_bf16(1).m)
  }.otherwise {
    muler_a_s1(0) := Cat(0.U, a_fp8(1)(0).m, 0.U(12.W), a_fp8(0)(0).m)
    muler_a_s1(1) := Cat(0.U, a_fp8(1)(1).m, 0.U(12.W), a_fp8(0)(1).m)
    muler_b_s1(0) := Cat(0.U, b_fp8(1)(0).m, 0.U(4.W),  b_fp8(0)(0).m)
    muler_b_s1(1) := Cat(0.U, b_fp8(1)(1).m, 0.U(4.W),  b_fp8(0)(1).m)
  }

  // Core Multiplier
  val muler_s2 = VecInit.tabulate(2)(i => {
    val muler = Reg(UInt(45.W))
    muler := muler_a_s1(i) * muler_b_s1(i)
    muler
  })

  // Result View
  val cm_bf16_s2 = VecInit.tabulate(2, 2)((m, n) => {
    muler_s2(n)(m * 16 + 15, m * 16)
  })
  val cm_fp8_s2 = VecInit.tabulate(2, 2, 2)((m, n, k) => {
    muler_s2(k)(16 * m + 8 * n + 7, 16 * m + 8 * n)
  })

  /* Exponent Computation Logic for FP8/BF16 */
  // Get All added Exponent
  val c_exp_s0 = VecInit.tabulate(2,2)((m, n) => {
    val hi, lo = Wire(UInt(5.W))
    val hi_sgn, lo_sgn = Wire(Bool())
    hi_sgn := a_fp8(m)(1).s ^ b_fp8(n)(1).s
    lo_sgn := a_fp8(m)(0).s ^ b_fp8(n)(0).s
    when(bf16) {
      hi := a_bf16(m).f(7, 4) +& b_bf16(n).f(7, 4)
      lo := a_bf16(m).f(3, 0) +& b_bf16(n).f(3, 0)
      hi_sgn := a_bf16(m).s ^ b_bf16(n).s
      lo_sgn := a_bf16(m).s ^ b_bf16(n).s
    }.otherwise {
      hi := a_fp8(m)(1).f +& b_fp8(n)(1).f
      lo := a_fp8(m)(0).f +& b_fp8(n)(0).f
    }
    Cat(hi_sgn, lo_sgn, hi, lo)
  })
  val c_exp_s1 = RegNext(c_exp_s0)

  // Get Align Shift information
  // Get Max
  val c_exp_max_s1 = VecInit.tabulate(2, 2)((m,n) => {
    val raw = c_exp_s1(m)(n)
    val hi_sgn = raw(11)
    val lo_sgn = raw(10)
    val hi = raw(9, 5)
    val lo = raw(4, 0)
    val diff = hi.asSInt -& lo.asSInt
    val lo_is_bigger = diff(5).asBool
    val max = Cat(0.U(4.W), Mux(lo_is_bigger, lo, hi))
    val diff_abs = Mux(lo_is_bigger, -diff, diff).asUInt(4, 0) // 0 - 30
    Cat(
      Mux(lo_is_bigger | bf16_s1, Cat(lo_sgn, hi_sgn), Cat(hi_sgn, lo_sgn)),
      Mux(
      bf16_s1,
      Cat(0.U(1.W),            Cat(hi, 0.U(4.W)) + Cat(0.U(4.W), lo), 0.U(5.W)),
      Cat(lo_is_bigger.asUInt,                                   max, diff_abs)
    ))
  })
  val c_exp_max_s2 = RegNext(c_exp_max_s1)

  // Aligning Logic
  // A Register, Do Not Shift
  val c_s2 = VecInit.tabulate(2, 2)((m,n) => {
    val raw = c_exp_max_s2(m)(n)
    val diff = raw(4, 0)
    val max  = raw(13, 5)
    val flip_ab = raw(14)
    val a_sgn = raw(16)
    val a = Cat(0.U(1.W), cm_fp8_s2(m)(n)(flip_ab)).asSInt
    val b_sgn = raw(15)
    val b = Cat(cm_fp8_s2(m)(n)(1.U-flip_ab), 0.U((wI - 9).W))
    val b_shifter = converter.shiftRight(b, diff)
    val b_shifted = Mux(enableStickBit.B, b_shifter._1 | b_shifter._2.asUInt, b_shifter._1)
    val b_merge = Cat(0.U(1.W), Mux(bf16_s2, Cat(cm_bf16_s2(m)(n), 0.U((wI - 17).W)), b_shifted)).asSInt
    Cat(max, Mux(a_sgn, -a, a) , Mux(b_sgn, -b_merge, b_merge)) // Exp(9), Signed MantissaA(9), Signed MantissaB(wI)
  })
  val c_s3 = RegNext(c_s2)

  // Output Logic
  for(m <- 0 until 2) {
    for(n <- 0 until 2) {
      val raw = c_s3(m)(n)
      val mantB = raw(wI - 1, 0).asSInt
      val mantA = Cat(raw(wI + 8, wI), 0.U((wI - 9).W)).asSInt
      val exp = raw(wI + 9 + 8, wI + 9)
      val frac = mantA + mantB

      when(bf16_s3) {
        c_exp(m)(n) := RegNext(exp(8, 9-wE))
      }.otherwise {
        c_exp(m)(n) := RegNext(exp(8, 9-wE) + 240.U(9.W)(8, 9-wE)) // Aligned to BF16 Exp
      }
      c_frac(m)(n) := RegNext(frac(wI - 1, wI - wF).asSInt)
    }
  }

}

object FP8BF16FusedDPm2n2k2Unit extends App {
  ChiselStage.emitSystemVerilogFile(
    new FP8BF16FusedDPm2n2k2Unit(9, 17, 17, false),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}
