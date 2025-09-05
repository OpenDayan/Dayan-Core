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
  val realK = K / tK    // real K Count
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
  val s4_scale = RegNext(s3_scale)
  val s5_scale = RegNext(s4_scale)
  val s6_scale = RegNext(s5_scale)
  val s7_scale = RegNext(s6_scale)

//  val s1_cin_exp = RegNext(cin_exp)
//  val s2_cin_exp = RegNext(s1_cin_exp)
//  val s3_cin_exp = RegNext(s2_cin_exp)
//  val s4_cin_exp = RegNext(s3_cin_exp)
  val s4_cin_exp = IO(Input(Vec(M, Vec(N, SInt(wE.W)))))   // Bias == 0
  val s5_cin_exp = RegNext(s4_cin_exp)

//  val s1_cin_frac = RegNext(cin_frac)
//  val s2_cin_frac = RegNext(s1_cin_frac)
//  val s3_cin_frac = RegNext(s2_cin_frac)
//  val s4_cin_frac = RegNext(s3_cin_frac)
  val s4_cin_frac = IO(Input(Vec(M, Vec(N, SInt(wF.W)))))
  val s5_cin_frac = RegNext(s4_cin_frac)
  val s6_cin_frac = RegNext(s5_cin_frac)

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
  // s2: Caculate Max exp and diff per Group
  val s2_exp = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)( rsk => {
    mulMatrix(m / tM)(n / tN)(g)(rsk).c_exp_s2(m % tM)(n % tN)
    }))
  // Signed 9 Bit with Bias == 0 [-256, 255]
  val s2_exp_max = VecInit.tabulate(M, N, G)((m, n, g) => {
    s2_exp(m)(n)(g).reduceTree((l, r) => Mux(l < r, r, l))
  })
  val s3_exp = RegNext(s2_exp)
  val s3_exp_max = RegNext(s2_exp_max)
  // Unsigned 9 Bit [0, 511]
  val s3_exp_diff = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)(rsk => {
    (s3_exp_max(m)(n)(g) - s3_exp(m)(n)(g)(rsk)).asUInt
  }))

//  val s4_exp_max = RegNext(s3_exp_max)
  val next_s4_exp = VecInit.tabulate(M, N, G)((m, n, g) => {
    converter.clipAdd(s3_scale(m)(n)(g), s3_exp_max(m)(n)(g), 255.S, -256.S)(8, 0).asSInt
  })
  val s4_exp = RegNext(next_s4_exp)
  val s4_exp_diff = RegNext(s3_exp_diff)
  // width: wMul, format: S1.(wMul-1)
  val s4_frac = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)(rsk => {
    mulMatrix(m / tM)(n / tN)(g)(rsk).c_frac_s4(m % tM)(n % tN)
  }))

  // Fraction Reduce Network0
  // wMul -> wShifter+log2Ceil(realSK)
  // S1.(wMul-1) -> S(1 + log2Ceil(realSK)).(wShifter-1)
  val reduceCore0 = (0 until M).map(m => (0 until N).map(n => (0 until G).map(g => {
    val core = Module(new FPFracReduceNetwork(n = realSK, wF = wMul, wI = wShifter, enableStickBit = enableStickBit))
    core.frac_in := s4_frac(m)(n)(g)
    core.diff := s4_exp_diff(m)(n)(g)
    core
  })))

  val next_s5_exp_max = VecInit.tabulate(M, N)((m, n) => {
    VecInit.tabulate(G + 1)(i => {
      var r = SInt()
      if(i == G) {
        r = s4_cin_exp(m)(n)
      } else {
        r = s4_exp(m)(n)(i)
      }
      r
    }).reduceTree((l, r) => Mux(l < r, r, l))
  })
  val s5_exp_max = RegNext(next_s5_exp_max)
  val s5_exp = RegNext(s4_exp)

  val next_s6_exp_diff = VecInit.tabulate(M, N, G)((m, n, g) => {
    (s5_exp_max(m)(n) - s5_exp(m)(n)(g))(8, 0).asUInt
  })
  val next_s6_cin_diff = VecInit.tabulate(M, N)((m, n) => {
    (s5_exp_max(m)(n) - s5_cin_exp(m)(n)).asUInt
  })
  val s6_cin_diff = RegNext(next_s6_cin_diff)
  val s6_exp_diff = RegNext(next_s6_exp_diff)
  val s6_exp = RegNext(s5_exp_max)
  val s7_exp = RegNext(s6_exp)
  val s8_exp = RegNext(s7_exp)

  // Two Cycle Reduce(Round0)
  // S(1 + log2Ceil(realSK)).(wShifter-1)
  val s6_frac = VecInit.tabulate(M, N, G)((m, n, g) => reduceCore0(m)(n)(g).frac_out)

  // S(1 + log2Ceil(realSK)).(wShifter-1) -> S(1 + log2Ceil(realSK) + log2Ceil(G + 1)).(wShifter-1)
  require(s6_frac(0)(0)(0).getWidth == (wShifter + log2Ceil(realSK)))
  val reduceCore1 = (0 until M).map(m => (0 until N).map(n => {
    val core = Module(new FPFracReduceNetwork(n = G + 1,
      wF = s6_frac(0)(0)(0).getWidth, wI = s6_frac(0)(0)(0).getWidth, enableStickBit = true))
    core.frac_in := VecInit.tabulate(G + 1)(i => {
      var r = SInt()
      if(i == G) {
        // wF -> wShifter + log2Ceil(realSK)
        r = Cat(s6_cin_frac(m)(n), 0.S((wShifter + log2Ceil(realSK) - wF).W)).asSInt
      } else {
        r = s6_frac(m)(n)(i)
      }
      r
    })
    core.diff := VecInit.tabulate(G + 1)(i => {
      var r = UInt()
      if(i == G) {
        r = s6_cin_diff(m)(n)
      } else {
        r = s6_exp_diff(m)(n)(i)
      }
      r
    })
    core
  }))

  // Two Cycle Reduce
  // S(1 + log2Ceil(realSK) + log2Ceil(G + 1)).(wShifter-1)
  val s8_frac = VecInit.tabulate(M, N)((m, n) => reduceCore1(m)(n).frac_out)

  // Output Rounding
  // CLZ
  val s8_frac_sign = VecInit.tabulate(M, N)((m, n) => s8_frac(m)(n)(wShifter + log2Ceil(realSK) + log2Ceil(G + 1) - 1).asBool)
  val s8_frac_abs = VecInit.tabulate(M, N)((m, n) => Mux(s8_frac_sign(m)(n), -s8_frac(m)(n), s8_frac(m)(n)).asUInt)
  val s8_frac_clz = VecInit.tabulate(M, N)((m, n) => CLZ(s8_frac_abs(m)(n)))
  val s8_frac_iszero = VecInit.tabulate(M, N)((m, n) => s8_frac(m)(n) === 0.S)
  //  U(wShifter + log2Ceil(realSK) + log2Ceil(G + 1)) -> U(wF-1)
  val s8_frac_shifted = VecInit.tabulate(M, N)((m, n) => Cat(0.U(1.W),
    (s8_frac_abs(m)(n) << s8_frac_clz(m)(n))(wShifter + log2Ceil(realSK) + log2Ceil(G + 1) - 1, (wShifter + log2Ceil(realSK) + log2Ceil(G + 1) - 1) + 1 - (wF - 1))
  ).asSInt)

  val next_s9_frac = VecInit.tabulate(M, N)((m, n) => {
    Mux(s8_frac_sign(m)(n), -s8_frac_shifted(m)(n), s8_frac_shifted(m)(n))
  })
  val next_s9_exp = VecInit.tabulate(M, N)((m, n) => {
    val cur_exp = s8_exp(m)(n)
    Mux(s8_frac_iszero(m)(n), -256.S, converter.clipSub(cur_exp, s8_frac_clz(m)(n).zext - (log2Ceil(realSK) + log2Ceil(G+1) + 1).S, 255.S, -256.S))(8,0).asSInt
  })

  val s9_exp = RegNext(next_s9_exp)
  val s9_frac = RegNext(next_s9_frac)

  // Result Interface
  val cout_exp = IO(Output(Vec(M, Vec(N, SInt(wE.W))))) // Bias == 127 - 2 == 125
  val cout_frac = IO(Output(Vec(M, Vec(N, SInt(wF.W)))))

  // Output Assembling
  cout_exp := s9_exp
  cout_frac := s9_frac

  // Debug Interface
  val fp32Dbg = IO(Output(Vec(M, Vec(N, UInt(32.W)))))
  for(m <- 0 until M) {
    for(n <- 0 until N) {
      // For Debugger: Temporarly Align To FP32 for better Debug View
      if(debugFP32Output) {
        val fp32_view_module = Module(new FPRundingModule(wE, wF, 0))
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
      M = 2, N = 2, K = 4, sK = 2,
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

