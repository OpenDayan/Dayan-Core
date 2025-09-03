package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Generic Module, Support tM tN Configuration.
// In Xilinx FPGA: tM==tN==2, for better Utilization of DSP48E2
// In Asic Flow: tM==tN==1, for smaller area and power consumption
// wE: width of Exponent
// wF: width of Fraction (Signed)
// wI: width of Inner Fraction Adder for FP8
class FP8BF16FusedDPUnit[S <: Module with U8U4FusedMxNyKzMul]
(
  mulGen: (Int, Int, Int) => S,
  tM: Int = 2, tN: Int = 2, tK: Int = 2,
  wE: Int = 9, wF: Int = 24, wI: Int = 24, enableStickBit: Boolean = true, debugFP32Output: Boolean = false
) extends Module {
  val latency = 4
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
  val bf16_s4 = RegNext(bf16_s3)
  dontTouch(bf16_s4)

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
  val next_c_s1 = VecInit.tabulate(tM, tN)((m, n) => {
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
    val r = Wire(new Bundle {
      val hi_sgn = Bool()
      val hi = UInt()
      val lo_sgn = Bool()
      val lo = UInt()
    })
    r.hi_sgn := hi_sgn
    r.hi := hi
    r.lo_sgn := lo_sgn
    r.lo := lo
    r
  })
  val c_s1 = RegNext(next_c_s1)

  // Get Align Shift information
  // Get Max
  val next_c_s2 = VecInit.tabulate(tM, tN)((m, n) => {
    val raw = c_s1(m)(n)
    val hi_sgn = raw.hi_sgn
    val lo_sgn = raw.lo_sgn
    val hi = raw.hi
    val lo = raw.lo
    val diff = hi.zext - lo.zext
    val hi_is_bigger = !diff(5).asBool
    val max = Cat(0.U(4.W), Mux(hi_is_bigger, hi, lo)) + 241.U
    val diff_abs = Mux(hi_is_bigger, diff, -diff).asUInt(4, 0) // 0 - 30
    val r = Wire(new Bundle {
      val flip_01 = Bool()
      val sgn = UInt()
      val max = UInt()
      val diff = UInt()
    })
    r.flip_01 := hi_is_bigger
    r.sgn := Mux(!hi_is_bigger | bf16_s1, Cat(lo_sgn, hi_sgn), Cat(hi_sgn, lo_sgn))
    r.max := Mux(bf16_s1, Cat(hi, 0.U(4.W)) + Cat(0.U(4.W), lo), max)
    r.diff := diff_abs
    r
  })
  val c_s2 = RegNext(next_c_s2)

  // Aligning Logic
  // A Register, Do Not Shift
  val next_c_s3 = VecInit.tabulate(tM, tN)((m, n) => {
    val raw = c_s2(m)(n)
    val diff      = raw.diff
    val max       = raw.max
    val flip_01   = raw.flip_01
    val fp8_0_sgn = raw.sgn(1)
    val fp8_0 = mul_u4_result_s2(m)(n)(flip_01).zext                        // Fixed S2.6
    val fp8_1_sgn = raw.sgn(0)
    val fp8_1 = Cat(mul_u4_result_s2(m)(n)(1.U-flip_01), 0.U((wI - 10).W))  // Fixed Unsigned 2.(wI - 4)
    val fp8_1_shifter = converter.shiftRight(fp8_1, diff)                   // Fixed Unsigned 2.(wI - 4)
    val fp8_1_shifted = Mux(enableStickBit.B, fp8_1_shifter._1 | fp8_1_shifter._2.asUInt, fp8_1_shifter._1)
//    val fp8_1_shifted = fp8_1_shifter._1
//    val fp8_1_shifted = 0.U
    val bf16_frac = Cat(mul_u8_result_s2(m)(n), 0.U((wI - 17).W))                   // Fixed Unsigned 2.(wI - 3)
    val b_merge = Mux(bf16_s2, bf16_frac.zext, Cat(0.U, fp8_1_shifted).zext)        // Fixed Signed PS2.(wI - 4)
    // Exp(9), Signed MantissaA(9), Signed MantissaB(wI)
//    MixedVecInit(max, Mux(fp8_0_sgn, -fp8_0, fp8_0) , Mux(fp8_1_sgn, -b_merge, b_merge))
    val r = Wire(new Bundle{
      val max = UInt()
      val frac_0 = SInt()
      val frac_1 = SInt()
    })
    r.max := max
    r.frac_0 := Mux(fp8_0_sgn, -fp8_0, fp8_0)
    r.frac_1 := Mux(fp8_1_sgn, -b_merge, b_merge)
    r
  })
  val c_s3 = RegNext(next_c_s3)

  val next_c_s4 = VecInit.tabulate(tM, tN)((m, n) => {
    val raw = c_s3(m)(n)
    val fracMain = Cat(raw.frac_0, 0.U((wI - 10).W)).asSInt  // Fixed Signed  S2.(wI - 4)
    val fracSecond = raw.frac_1                              // Fixed Signed  S2.(wI - 4)
    val r = Wire(new Bundle {
      val exp = UInt()
      val frac = SInt()
    })
    // For FP8: S3.(wI - 3) => clip to S1.(wI - 3)
    r.exp := raw.max
    r.frac := Mux(bf16_s3, fracSecond, (fracMain +& fracSecond(wI - 2, 0).asSInt).asSInt)
    r
  })
  val c_s4 = RegNext(next_c_s4)

  // Output Logic

  val c_exp_s2 = IO(Output(Vec(tM, Vec(tN, UInt(wE.W)))))
  val c_exp_s3 = IO(Output(Vec(tM, Vec(tN, UInt(wE.W)))))
  val c_exp_s4 = IO(Output(Vec(tM, Vec(tN, UInt(wE.W)))))  // Packed m2n2-Exp Output, exp + 254 represent
  val c_frac_s4 = IO(Output(Vec(tM, Vec(tN, SInt(wF.W))))) // Packed m2n2-Frac Signed 3.(wF-3)
  val fp32Dbg = IO(Output(Vec(tM, Vec(tN, UInt(32.W)))))
  for(m <- 0 until tM) {
    for(n <- 0 until tN) {
      val raw = c_s4(m)(n)
      c_exp_s4(m)(n) := raw.exp
      c_frac_s4(m)(n) := raw.frac

      // Early Access to exp For Dot-Product Array
      c_exp_s2(m)(n) := c_s2(m)(n).max
      c_exp_s3(m)(n) := c_s3(m)(n).max

      // For Debugger: Temporarly Align To FP32 for better Debug View
      if(debugFP32Output) {
        val fp32_view_module = Module(new FPRundingModule(wE, wF, 125))
        fp32_view_module.exp := raw.exp
        fp32_view_module.frac := raw.frac.asUInt
        fp32Dbg(m)(n) := fp32_view_module.fp
      } else {
        fp32Dbg(m)(n) := 0.U
      }
    }
  }

}

object FP8BF16FusedDPUnit extends App {
  ChiselStage.emitSystemVerilog(
    new FP8BF16FusedDPUnit((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK), debugFP32Output = true),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/FP8BF16FusedDPUnit"
    )
  )
}
