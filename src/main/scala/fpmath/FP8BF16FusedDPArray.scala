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
M: Int = 4, N: Int = 8, K: Int = 32, tM: Int = 2, tN: Int = 2, tK: Int = 2,
wE: Int = 9, wF: Int = 25, wI: Int = 24, wAdder: Int = 32, enableStickBit: Boolean = true
) extends Module {
  // Input Matrix
  val a = IO(Input(Vec(M, Vec(K, UInt(8.W)))))
  val b = IO(Input(Vec(N, Vec(K, UInt(8.W)))))
  val a_scale = IO(Input(Vec(M, SInt(9.W)))) // Bias == 0 [-127, 128]
  val b_scale = IO(Input(Vec(N, SInt(9.W)))) // Bias == 0 [-127, 128]
  val cin_exp = IO(Input(Vec(M, Vec(N, UInt(wE.W)))))   // Bias == 127 - 2 == 125
  val cin_frac = IO(Input(Vec(M, Vec(N, SInt(wF.W)))))

  // DataPipeline
  val MaxPipeStages = 8
  val a_scale_pipe = pipe.genPipe(a_scale, MaxPipeStages)
  val b_scale_pipe = pipe.genPipe(b_scale, MaxPipeStages)
  val cin_exp_pipe = pipe.genPipe(cin_exp, MaxPipeStages)
  val cin_frac_pipe = pipe.genPipe(cin_frac, MaxPipeStages)

  // Control flow
  val bf16 = IO(Input(Bool()))

  // Result Interface
  val cout_exp = IO(Output(Vec(M, Vec(N, UInt(wE.W))))) // Bias == 127 - 2 == 125
  val cout_frac = IO(Output(Vec(M, Vec(N, SInt(wF.W)))))

  // Compute On Input

  val mulMatrix = (0 until M / tM).map(tm => (0 until N / tN).map(tn => (0 until K / tK).map(tk => {
    val mulUnitModule = Module(new FP8BF16FusedDPUnit(mulGen, tM = tM, tN = tN, tK = tK,
      wE = wE, wF = wI, wI = wI, enableStickBit = enableStickBit))
    mulUnitModule.bf16 := bf16
//    mulUnitModule.a_in := a.slice(tm * tM, (tm + 1) * tM).map(_.slice(tk * tK, (tk + 1) * tK))
    mulUnitModule.a_in := VecInit.tabulate(tM, tK)((um, uk) => a(tm * tM + um)(tk * tK + uk))
//    mulUnitModule.b_in := b.slice(tn * tN, (tn + 1) * tN).map(_.slice(tk * tK, (tk + 1) * tK))
    mulUnitModule.b_in := VecInit.tabulate(tN, tK)((un, uk) => b(tn * tN + un)(tk * tK + uk))
    mulUnitModule
  })))
  var expLatency = mulMatrix(0)(0)(0).latency - 1
  var fracLatency = mulMatrix(0)(0)(0).latency

  // Align Logic
  val scaleExp = VecInit.tabulate(M, N)((m, n) => {
//    val in = a_scale_pipe(expLatency)(m) +& b_scale_pipe(expLatency)(n)
//    Mux(in(9,8) === 2.U, 255.S, in)
    converter.clipAdd(a_scale_pipe(expLatency)(m), b_scale_pipe(expLatency)(n),
      255.S, converter.getMinSInt(a_scale.getWidth + 1))
  })
  // Get Max Exp Value for each (m, n) pair.
  // Reduce Input Vec(K / tK + 1)
  val maxExp = VecInit.tabulate(M, N)((m, n) => {
    val rawExp = VecInit.tabulate(K / tK + 1)( tk => {
      var r: UInt = 0.U
      if (tk == (K / tK)) {
        r = cin_exp_pipe(expLatency)(m)(n)
      } else {
        r = mulMatrix(m / tM)(n / tN)(tk).c_exp(m % tM)(n % tN)
      }
      r
    })
    rawExp.reduceTree((l, r) => {
      Mux(l > r, l, r)
    })
  })
  val diffExp = VecInit.tabulate(M, N, K / tK + 1)((m, n, tk) => {
    var r = 0.U
    if(tk == (K / tK)){
      r = maxExp(m)(m) - cin_exp_pipe(expLatency)(m)(n)
    } else {
      r = maxExp(m)(m) - mulMatrix(m/tM)(n/tN)(tk).c_exp(m%tM)(n%tN)
    }
    r
  })

  val maxExpPipe  = pipe.genPipe(RegNext(maxExp), 2)
  val diffExpPipe = pipe.genPipe(RegNext(diffExp), 1)

  val fracS0 = VecInit.tabulate(M, N, K / tK + 1)((m, n, tk) => {
    var valFrac = 0.S
    if(tk == (K / tK)) {
      valFrac = Cat(cin_frac_pipe(fracLatency)(m)(n), 0.S((wAdder - wE).W)).asSInt
    } else {
      valFrac = Cat(mulMatrix(m/tM)(n/tN)(tk).c_frac(m%tM)(n%tN), 0.S((wAdder - wI).W)).asSInt
    }
    val shiftedFrac = converter.shiftRightSigned(valFrac, diffExpPipe(0)(m)(n)(tk))
    Mux(enableStickBit.B, shiftedFrac._1.asUInt | shiftedFrac._2.asUInt, shiftedFrac._1.asUInt).asSInt
  })
  val fracS1 = RegNext(fracS0)

  // Adder Tree
  // TODO Optimize me: Using CSA or Other Structure
  val fracMergeS1 = VecInit.tabulate(M, N)((m, n) => {fracS1(m)(n).reduceTree((l, r) => {l +& r})})
  val fracS2 = RegNext(fracMergeS1)

  val wMerge = fracS2(0)(0).getWidth

  // Output Rounding(LZC) for Frac
  val fracSignS2 = VecInit.tabulate(M, N)((m, n) => fracS2(m)(n)(wMerge-1))
  val fracAbsS2 = VecInit.tabulate(M, N)((m, n) => Mux(fracS2(m)(n)(wMerge-1).asBool,
    -fracS2(m)(n), fracS2(m)(n)).asUInt)
  val fracLZCS2 = VecInit.tabulate(M, N)((m, n) => {CLZ(fracAbsS2(m)(n))})
  val fracShiftedS2 = VecInit.tabulate(M, N)((m, n) => fracAbsS2(m)(n) << fracLZCS2(m)(n))
  val fracSignedS2 = VecInit.tabulate(M, N)((m, n) => {
    val c = fracShiftedS2(m)(n).zext
    Mux(fracSignS2(m)(n), -c, c)
  })

  // Output Rounding for Exp
  val expS2 = VecInit.tabulate(M, N)((m ,n) => {
    val curMax = maxExpPipe(2)(m)(n).zext
    val diff = fracLZCS2(m)(n).zext - (wMerge - wAdder).S
    converter.clipAdd(curMax, diff, converter.getMaxSInt(wE + 1), 0.S)(wE - 1, 0)
  })

  // Output Assembling
  cout_exp  := RegNext(expS2)
  cout_frac := RegNext(VecInit.tabulate(M,N)((m,n) => fracSignedS2(m)(n)(wMerge-1, wMerge-wF).asSInt))

}

object FP8BF16FusedDPArray extends App {
  ChiselStage.emitSystemVerilog(
    new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
      M = 4, N = 8, K = 32
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

