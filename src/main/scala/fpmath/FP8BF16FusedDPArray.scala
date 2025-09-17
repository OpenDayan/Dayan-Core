package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.pipe

// M: M Dim for FP8/BF16
// N: N Dim for FP8/BF16
// K: K Dim for FP8
// tM: Compute Unit's M Dim
// tN: Compute Unit's M Dim
// tK: Compute Unit's M Dim
// wE: width of Exp
// wMul: width of Compute Unit Data path
// wShifter: width of Adder Tree0 Input
class FP8BF16FusedDPArray[S <: Module with U8U4FusedMxNyKzMul](
mulGen: (Int, Int, Int) => S,
M: Int = 4, N: Int = 8, K: Int = 32, tM: Int = 2, tN: Int = 2, tK: Int = 2, sK: Int = 32,
wE: Int = 9, wF: Int = 25, wMul: Int = 24, wShifter: Int = 32, enableStickBit: Boolean = true,
debugFP32Output: Boolean = false
) extends Module {

  // Constant Values
  val G = K / sK
  val realK = K / tK    // real K per MN
  val realSK = sK / tK  // real K Count Per Group

  // Input Matrix
  val a = IO(Input(Vec(M, Vec(K, UInt(8.W)))))
  val b = IO(Input(Vec(N, Vec(K, UInt(8.W)))))
  // For Debug: BF16 View
  val a_bf16 = VecInit.tabulate(M, K / 2)((m, k) => {
    Cat(a(m)(2*k+1), a(m)(2*k))
  })
  val b_bf16 = VecInit.tabulate(N, K / 2)((n, k) => {
    Cat(b(n)(2*k+1), b(n)(2*k))
  })
  dontTouch(a_bf16)
  dontTouch(b_bf16)

  require(K % sK == 0, "K Must Be Aligned to sK.")

  val a_scale = IO(Input(Vec(M, Vec(G, SInt(9.W))))) // Bias == 0 [-127, 128]
  val b_scale = IO(Input(Vec(N, Vec(G, SInt(9.W))))) // Bias == 0 [-127, 128]
  // Compute Scale Matrix Bias == 0 Signed 9b [-254, 255]
  val s0_scale = VecInit.tabulate(M, N, G)((m,n,g) => {
    converter.clipAdd(a_scale(m)(g), b_scale(n)(g), 255.S, -256.S)(8, 0).asSInt
  })

//  val cin_exp = IO(Input(Vec(M, Vec(N, SInt(wE.W)))))   // Bias == 0
//  val cin_frac = IO(Input(Vec(M, Vec(N, SInt(wF.W)))))

  // scale reduce: Select Max Scale
  // Signed 9b [-254, 255]
  val s1_scale = RegNext(s0_scale)
  val s2_scale = RegNext(s1_scale)
  val s3_scale = RegNext(s2_scale)
//  val s4_scale = RegNext(s3_scale)
//  val s5_scale = RegNext(s4_scale)
//  val s6_scale = RegNext(s5_scale)
//  val s7_scale = RegNext(s6_scale)

//  val s1_cin_exp = RegNext(cin_exp)
//  val s2_cin_exp = RegNext(s1_cin_exp)
//  val s3_cin_exp = RegNext(s2_cin_exp)
//  val s4_cin_exp = RegNext(s3_cin_exp)
//  val s5_cin_exp = RegNext(s4_cin_exp)

//  val s1_cin_frac = RegNext(cin_frac)
//  val s2_cin_frac = RegNext(s1_cin_frac)
//  val s3_cin_frac = RegNext(s2_cin_frac)
//  val s4_cin_frac = RegNext(s3_cin_frac)
//  val s5_cin_frac = RegNext(s4_cin_frac)
//  val s6_cin_frac = RegNext(s5_cin_frac)

  // Control flow
  val valid = IO(Input(Bool()))  // Clock Enable For First Stage
  val bf16 = IO(Input(Bool()))

  // Compute On Input
  val mulMatrix = (0 until M / tM).map(tm => (0 until N / tN).map(tn => (0 until G).map(
    g => (0 until realSK).map(
      u => {
    val mulUnitModule = Module(new FP8BF16FusedDPUnit(mulGen, tM = tM, tN = tN, tK = tK,
      wE = wE, wF = wMul, wI = wMul, enableStickBit = enableStickBit, debugFP32Output = true))
        mulUnitModule.bf16 := bf16
        mulUnitModule.valid := valid
        mulUnitModule.a_in := VecInit.tabulate(tM, tK)((um, uk) => a(tm * tM + um)((g * realSK + u) * tK + uk))
        mulUnitModule.b_in := VecInit.tabulate(tN, tK)((un, uk) => b(tn * tN + un)((g * realSK + u) * tK + uk))
        mulUnitModule
  }))))

  // Align Logic
  // s1: Caculate Max exp and diff per Group
  val s1_exp = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)( rsk => {
    mulMatrix(m / tM)(n / tN)(g)(rsk).c_exp_s1(m % tM)(n % tN)
    }))
  val s2_exp = RegNext(s1_exp)
  // Signed 9 Bit with Bias == 0 [-256, 255]
  val s1_exp_max_group = VecInit.tabulate(M, N, G)((m, n, g) => {
    s1_exp(m)(n)(g).reduceTree((l, r) => Mux(l < r, r, l))
  })
  val s2_exp_max_group = RegNext(s1_exp_max_group)
  val s2_exp_max = VecInit.tabulate(M, N)((m, n) => {
    VecInit.tabulate(G)(g => s2_exp_max_group(m)(n)(g) + s2_scale(m)(n)(g)).reduceTree((l, r) => Mux(l < r, r, l))
  })
  val s3_exp_max = RegNext(s2_exp_max)
  val s3_exp = RegNext(s2_exp)
  // Unsigned 9 Bit [0, 511]
  val s3_exp_diff = VecInit.tabulate(M, N, realK)((m, n, k) => {
    (s3_exp_max(m)(n) - s3_exp(m)(n)(k / realSK)(k % realSK) - s3_scale(m)(n)(k / realSK)).asUInt
  })

//  val s4_exp_max = RegNext(s3_exp_max)
  val s4_exp = RegNext(s3_exp_max)
  val s4_exp_diff = RegNext(s3_exp_diff)
  // width: wMul, format: S1.(wMul-1)
  val s4_frac = VecInit.tabulate(M, N, realK)((m, n, k) => {
    mulMatrix(m / tM)(n / tN)(k / realSK)(k % realSK).c_frac_s4(m % tM)(n % tN)
  })

  // Fraction Reduce Network0
  // wMul -> wShifter+log2Ceil(realK)
  // S1.(wMul-1) -> S(1 + log2Ceil(realK)).(wShifter-1)
  val reduceCore0 = (0 until M).map(m => (0 until N).map(n => {
    val core = Module(new FPFracReduceNetwork(n = realK, wF = wMul, wI = wShifter, enableStickBit = enableStickBit))
    core.frac_in := VecInit.tabulate(realK)(k =>  s4_frac(m)(n)(k))
    core.diff := VecInit.tabulate(realK)(k => s4_exp_diff(m)(n)(k))
    core
  }))
  // reduce start in s4 stage, end in r0 stage, s4 -> r0 -- reduceCoreLatency
  val r0Latency = 2 + 4 + reduceCore0(0)(0).latency
  val rpipe_exp = pipe.genPipe(s4_exp, reduceCore0(0)(0).latency)

  val r0_exp = rpipe_exp(reduceCore0(0)(0).latency)
  // S(1 + log2Ceil(realK)).(wShifter-1)
  val r0_frac = VecInit.tabulate(M, N)((m, n) => reduceCore0(m)(n).frac_out)

  // Add CIN
  // TODO Note: cin_fwd is NOT USED~
//  val r0_cin_fwd = IO(Input(Bool()))                          // Use r2_cout directly as r0_cin
  val r0_cin_exp_in = IO(Input(Vec(M, Vec(N, SInt(wE.W)))))   // Bias == 0
  val r0_cin_frac_in = IO(Input(Vec(M, Vec(N, SInt(wF.W)))))  // S1.(wF-1)

  val cout_exp = IO(Output(Vec(M, Vec(N, SInt(wE.W))))) // Bias == 127 - 2 == 125
  val cout_frac = IO(Output(Vec(M, Vec(N, SInt(wF.W)))))
//  val r0_cin_exp = Mux(r0_cin_fwd, cout_exp, r0_cin_exp_in)
//  val r0_cin_frac = Mux(r0_cin_fwd, cout_frac, r0_cin_frac_in)
  val r0_cin_exp = r0_cin_exp_in
  val r0_cin_frac = r0_cin_frac_in

  // Align with CIN
  val r0_cin_frac_ext = VecInit.tabulate(M, N)((m, n) => {
    val r = Wire(SInt((wShifter + log2Ceil(realK)).W))
    r := Cat(r0_cin_frac(m)(n), 0.S((wShifter - wF).W)).asSInt
    r
  }
  )
  // s9 - s9 => s10
  val r0_cin_exp_diff = VecInit.tabulate(M, N)((m, n) =>
    (r0_cin_exp(m)(n) -& r0_exp(m)(n))
  )
  val r0_frac_shifted = VecInit.tabulate(M, N)((m, n) => {
    val shifter = converter.shiftRightSigned(r0_frac(m)(n), r0_cin_exp_diff(m)(n)(8, 0))
    Mux(r0_cin_exp_diff(m)(n)(9).asBool, r0_frac(m)(n), shifter._1.asSInt)
  }
  )
  val r0_cin_frac_shifted = VecInit.tabulate(M, N)((m, n) => {
    val shifter = converter.shiftRightSigned(r0_cin_frac_ext(m)(n), (-r0_cin_exp_diff(m)(n))(8, 0))
    Mux(r0_cin_exp_diff(m)(n)(9).asBool, shifter._1.asSInt, r0_cin_frac_ext(m)(n))
  })
  require(r0_cin_frac_shifted(0)(0).getWidth == r0_frac_shifted(0)(0).getWidth)
  val next_r1_exp = VecInit.tabulate(M, N)((m, n) =>
    Mux(r0_cin_exp_diff(m)(n)(9).asBool, r0_exp(m)(n), r0_cin_exp(m)(n))
  )
  // S(2 + log2Ceil(realK)).(wShifter-1)
  val next_r1_frac = VecInit.tabulate(M, N)((m, n) => {
    r0_frac_shifted(m)(n) +& r0_cin_frac_shifted(m)(n)
  })
  val next_r1_frac_sgn = VecInit.tabulate(M, N)((m, n) => {
    next_r1_frac(m)(n)(wShifter + log2Ceil(realK) + 1 - 1).asBool
  })
  val next_r1_frac_abs = VecInit.tabulate(M, N)((m, n) => {
    Mux(next_r1_frac_sgn(m)(n), -next_r1_frac(m)(n), next_r1_frac(m)(n))
  })
  val r1_exp = RegNext(next_r1_exp)
  val r1_frac_sgn = RegNext(next_r1_frac_sgn)
  val r1_frac_abs = RegNext(next_r1_frac_abs)

  val r1_frac_clz = VecInit.tabulate(M, N)((m, n) => {
    CLZ(r1_frac_abs(m)(n).asUInt)
  })
  // S(2 + log2Ceil(realK)).(wShifter-1) -> wF-1
  val next_r2_frac = VecInit.tabulate(M, N)((m, n) => {
    val shifted = Cat(0.U(1.W),(
      r1_frac_abs(m)(n) <<
        r1_frac_clz(m)(n))(wShifter + log2Ceil(realK), (wShifter + log2Ceil(realK)) + 1 - (wF - 1))
    ).asSInt
    Mux(r1_frac_sgn(m)(n), -shifted, shifted)
  })
  val next_r2_exp = VecInit.tabulate(M, N)((m, n) => {
    val cur_exp = r1_exp(m)(n)
    Mux(r1_frac_abs(m)(n) === 0.S, -256.S,
      converter.clipSub(cur_exp, r1_frac_clz(m)(n).zext - (log2Ceil(realK) + 2).S, 255.S, -256.S)
    )(8,0).asSInt
  })
  val r2_frac = RegNext(next_r2_frac)
  val r2_exp = RegNext(next_r2_exp)

  // Result Interface
  // Output Assembling
  cout_exp := r2_exp
  cout_frac := r2_frac

  // Debug Interface
  val fp32Dbg = IO(Output(Vec(M, Vec(N, UInt(32.W)))))
  for(m <- 0 until M) {
    for(n <- 0 until N) {
      // For Debugger: Temporarly Align To FP32 for better Debug View
      if(debugFP32Output) {
        val fp32_view_module = Module(new FPRoundingModule(wE, wF, 0))
        fp32_view_module.exp := cout_exp(m)(n)
        fp32_view_module.frac := cout_frac(m)(n).asUInt
        fp32Dbg(m)(n) := fp32_view_module.fp
      } else {
        fp32Dbg(m)(n) := 0.U
      }
    }
  }

}

object FP8BF16FusedDPArray extends App {
  ChiselStage.emitSystemVerilog(
    new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
      M = 4, N = 2, K = 32, sK = 32,
      debugFP32Output = true
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/FP8BF16FusedDPArray"
    )
  )
}

