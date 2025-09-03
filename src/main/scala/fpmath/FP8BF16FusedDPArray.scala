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
// wI: width of Compute Unit Datapath
// wAddr: width of Adder Tree
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
  // Compute Scale Matrix
  val s0_scale = VecInit.tabulate(M, N, G)((m,n,g) => {
    converter.clipAdd(a_scale(m)(g), b_scale(n)(g),
      255.S, converter.getMinSInt(a_scale(0)(0).getWidth + 1))
  })

  val cin_exp = IO(Input(Vec(M, Vec(N, SInt(wE.W)))))   // Bias == 0
  val cin_frac = IO(Input(Vec(M, Vec(N, SInt(wF.W)))))

  // scale reduce: Select Max Scale
  // Signed 9b
  val s0_scale_max = VecInit.tabulate(M, N)((m,n) => {
    val newScaleMax = s0_scale(m)(n).reduceTree((l,r)=>Mux(l < r, r, l))
    val oldExp = cin_exp(m)(n)
    Mux(newScaleMax < oldExp, oldExp, newScaleMax)
  })
  val s1_scale     = RegNext(s0_scale)
  val s1_scale_max = RegNext(s0_scale_max)

  // scale diff: per-group difference
  // Signed 10b
  val s1_scale_diff = VecInit.tabulate(M, N, G)((m, n, g) => {
     s1_scale_max(m)(n) -& s1_scale(m)(n)(g) // -255 256
  })

  // DataPipeline
  val MaxPipeStages = 8
  val cin_exp_pipe = pipe.genPipe(cin_exp, MaxPipeStages)
  val cin_frac_pipe = pipe.genPipe(cin_frac, MaxPipeStages)

  // Control flow
  val bf16 = IO(Input(Bool()))

  // Compute On Input
  val mulMatrix = (0 until M / tM).map(tm => (0 until N / tN).map(tn => (0 until G).map(
    g => (0 until realSK).map(
      u => {
    val mulUnitModule = Module(new FP8BF16FusedDPUnit(mulGen, tM = tM, tN = tN, tK = tK,
      wE = wE, wF = wMul, wI = wMul, enableStickBit = enableStickBit, debugFP32Output = true))
    mulUnitModule.bf16 := bf16
    mulUnitModule.a_in := VecInit.tabulate(tM, tK)((um, uk) => a(tm * tM + um)((g * realSK + u) * tK + uk))
    mulUnitModule.b_in := VecInit.tabulate(tN, tK)((un, uk) => b(tn * tN + un)((g * realSK + u) * tK + uk))
    mulUnitModule
  }))))

  // Align Logic
  // s2: Caculate Max exp and diff per Group
  val s2_exp = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)( rsk => {
    mulMatrix(m / tM)(n / tN)(g)(rsk).c_exp_s2(m % tM)(n % tN)
    }))
  // Unsigned 9 Bit with Bias == 125
  val s2_exp_max = VecInit.tabulate(M, N, G)((m, n, g) => {
    s2_exp(m)(n)(g).reduceTree((l, r) => Mux(l < r, r, l))
  })
  val s3_exp = RegNext(s2_exp)
  val s3_exp_max = RegNext(s2_exp_max)
  // Unsigned 10 Bit
  val s3_exp_diff = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)(rsk => {
    s3_exp_max(m)(n)(g) -& s3_exp(m)(n)(g)(rsk)
  }))

  val s4_exp_max = RegNext(s3_exp_max)
  val s4_exp_diff = RegNext(s3_exp_diff)
  val s4_frac = VecInit.tabulate(M, N, G)((m, n, g) => VecInit.tabulate(realSK)(rsk => {
    mulMatrix(m / tM)(n / tN)(g)(rsk).c_frac_s4(m % tM)(n % tN)
  }))

  // Fraction Reduce Network
  val reduceCore0 = (0 until M).map(m => (0 until N).map(n => (0 until G).map(g => {
    val core = Module(new FPFracReduceNetwork(n = realSK, wF = wMul, wI = wShifter, enableStickBit = enableStickBit))
    core.frac_in := s4_frac(m)(n)(g)
    core.diff := s4_exp_diff(m)(n)(g)
    core
  })))

  // Two Cycle Reduce(Round0)
  val s6_frac = VecInit.tabulate(M, N, G)((m, n, g) => reduceCore0(m)(n)(g).frac_out)

  val reduceCore1 = (0 until M).map(m => (0 until N).map(n => {

  }))

  // Result Interface
  val cout_exp = IO(Output(Vec(M, Vec(N, UInt(wE.W))))) // Bias == 127 - 2 == 125
  val cout_frac = IO(Output(Vec(M, Vec(N, SInt(wF.W)))))

  // Output Assembling
//  cout_exp  := RegNext(expS2)
//  cout_frac := RegNext(VecInit.tabulate(M,N)((m,n) => fracSignedS2(m)(n)(wMerge-1, wMerge-wF).asSInt))

  // Debug Interface
  val fp32Dbg = IO(Output(Vec(M, Vec(N, UInt(32.W)))))
  for(m <- 0 until M) {
    for(n <- 0 until N) {
      // For Debugger: Temporarly Align To FP32 for better Debug View
      if(debugFP32Output) {
        val fp32_view_module = Module(new FPRundingModule(wE, wF, 125))
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
      M = 2, N = 2, K = 4,
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

