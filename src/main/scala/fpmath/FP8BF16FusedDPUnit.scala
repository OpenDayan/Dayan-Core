package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Designed for FPGA Usage
// wE: width of Exponent
// wF: width of Fraction (Signed)
// wI: width of Inner Fraction Adder for FP8
class FP8BF16FusedDPUnit[S <: Module with U8U4FusedMxNyKzMul]
(
  mulGen: (Int, Int, Int) => S,
  tM: Int = 2, tN: Int = 2, tK: Int = 2,
  wE: Int = 9, wF: Int = 24, wI: Int = 24, enableStickBit: Boolean = false
) extends Module {
  require(wF >= 17, "BF16 Should be full precise")
  require(wI >= wF, "Inner Fraction Adder should GE than output Fraction Width")
  require(tK == 2, "tK Must be 2 for now.")
  val a_in = IO(Input(Vec(tM, Vec(tK, UInt(8.W))))) // Packed m2k2-FP8 or m2k1-BF16 (m,k)
  val b_in = IO(Input(Vec(tN, Vec(tK, UInt(8.W)))))
  val bf16 = IO(Input(Bool()))                // Compute BF16 * BF16 Instead of FP8 * FP8

  // Control Flow
  val bf16_s1 = RegNext(bf16)
  val bf16_s2 = RegNext(bf16_s1)
  val bf16_s3 = RegNext(bf16_s2)

  val c_exp = IO(Output(Vec(tM, Vec(tN, UInt(wE.W)))))  // Packed m2n2-Exp Output, exp + 254 represent
  val c_frac = IO(Output(Vec(tM, Vec(tN, SInt(wF.W))))) // Packed m2n2-Frac

  val a_fp8 = VecInit.tabulate(tM,tK)((m, k) => converter.func_unpack_e4m3(a_in(m)(k)))
  val b_fp8 = VecInit.tabulate(tN,tK)((n, k) => converter.func_unpack_e4m3(b_in(n)(k)))
  for(m <- 0 until tM) {
    val a_bf16_view = Cat(a_in(m)(1), a_in(m)(0))
    dontTouch(a_bf16_view)
  }
  for(n <- 0 until tN) {
    val b_bf16_view = Cat(b_in(n)(1), b_in(n)(0))
    dontTouch(b_bf16_view)
  }
  val a_bf16 = VecInit.tabulate(tM)(m => converter.func_unpack_bf16(Cat(a_in(m)(1), a_in(m)(0))))
  val b_bf16 = VecInit.tabulate(tN)(n => converter.func_unpack_bf16(Cat(b_in(n)(1), b_in(n)(0))))

  /* Fraction Computation Logic */
  val fused_mul = Module(mulGen(tM, tN, tK))
  require(fused_mul.latency == 2, "Core Multiplier Latency Must Be 2.")
  val mul_u4_result_s2 = fused_mul.u4_out
  val mul_u8_result_s2 = fused_mul.u8_out
  fused_mul.u8 := bf16
  fused_mul.a_u4 := VecInit.tabulate(tM,tK)((m, k) => a_fp8(m)(k).m)
  fused_mul.b_u4 := VecInit.tabulate(tN,tK)((n, k) => b_fp8(n)(k).m)
  fused_mul.a_u8 := VecInit.tabulate(tM)(m => a_bf16(m).m)
  fused_mul.b_u8 := VecInit.tabulate(tN)(n => b_bf16(n).m)

  /* Exponent Computation Logic for FP8/BF16 */
  // Get All added Exponent
  val c_exp_s0 = VecInit.tabulate(tM, tN)((m, n) => {
    val hi, lo = Wire(UInt(5.W))
    val hi_sgn, lo_sgn = Wire(Bool())
    hi_sgn := a_fp8(m)(1).s ^ b_fp8(n)(1).s
    lo_sgn := a_fp8(m)(0).s ^ b_fp8(n)(0).s
    when(bf16) {
      hi := a_bf16(m).f(7, 4) +& b_bf16(n).f(7, 4)
      lo := a_bf16(m).f(3, 0) +& b_bf16(n).f(3, 0)
      hi_sgn := a_bf16(m).s ^ b_bf16(n).s
    }.otherwise {
      hi := a_fp8(m)(1).f +& b_fp8(n)(1).f
      lo := a_fp8(m)(0).f +& b_fp8(n)(0).f
    }
    Cat(hi_sgn, lo_sgn, hi, lo)
  })
  val c_exp_s1 = RegNext(c_exp_s0)

  // Get Align Shift information
  // Get Max
  val c_exp_max_s1 = VecInit.tabulate(tM, tN)((m,n) => {
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
  val c_s2 = VecInit.tabulate(tM, tN)((m,n) => {
    val raw = c_exp_max_s2(m)(n)
    val diff = raw(4, 0)
    val max  = raw(13, 5)
    val flip_ab = raw(14)
    val a_sgn = raw(16)
    val a = Cat(0.U(1.W), mul_u4_result_s2(m)(n)(flip_ab)).asSInt
    val b_sgn = raw(15)
    val b = Cat(mul_u4_result_s2(m)(n)(1.U-flip_ab), 0.U((wI - 9).W))
    val b_shifter = converter.shiftRight(b, diff)
    val b_shifted = Mux(enableStickBit.B, b_shifter._1 | b_shifter._2.asUInt, b_shifter._1)
    val b_merge = Cat(0.U(1.W), Mux(bf16_s2, Cat(mul_u8_result_s2(m)(n), 0.U((wI - 17).W)), b_shifted)).asSInt
    Cat(max, Mux(a_sgn, -a, a) , Mux(b_sgn, -b_merge, b_merge)) // Exp(9), Signed MantissaA(9), Signed MantissaB(wI)
  })
  val c_s3 = RegNext(c_s2)

  // Output Logic
  for(m <- 0 until tM) {
    for(n <- 0 until tN) {
      val raw = c_s3(m)(n)
      val mantB = raw(wI - 1, 0).asSInt
      val mantA = Cat(raw(wI + 8, wI), 0.U((wI - 9).W)).asSInt
      val exp = raw(wI + 9 + 8, wI + 9)
      val frac = Mux(bf16_s3, mantB, mantA + mantB)

      when(bf16_s3) {
        c_exp(m)(n) := RegNext(exp(8, 9-wE))
      }.otherwise {
        c_exp(m)(n) := RegNext(exp(8, 9-wE) + 240.U(9.W)(8, 9-wE)) // Aligned to BF16 Exp
      }
      c_frac(m)(n) := RegNext(frac(wI - 1, wI - wF).asSInt)
      // For Debugger: Temporarly Align To FP32 for better Debug View
      val fp32_view_module = Module(new FPRundingModule(wE, wF, 125))
      fp32_view_module.exp := c_exp(m)(n)
      fp32_view_module.frac := c_frac(m)(n).asUInt
      val fp32_view = fp32_view_module.fp
      dontTouch(fp32_view)
    }
  }

}

object FP8BF16FusedDPUnit extends App {
  ChiselStage.emitSystemVerilog(
    new FP8BF16FusedDPUnit((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/FP8BF16FusedDPUnit"
    )
  )
}
