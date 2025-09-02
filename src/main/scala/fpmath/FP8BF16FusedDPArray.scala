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
  val a_scale = IO(Input(Vec(M, UInt(8.W))))
  val b_scale = IO(Input(Vec(N, UInt(8.W))))
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
    mulUnitModule.a_in := a.slice(tm * tM, (tm + 1) * tM).map(_.slice(tk * tK, (tk + 1) * tK))
    mulUnitModule.b_in := b.slice(tn * tN, (tn + 1) * tN).map(_.slice(tk * tK, (tk + 1) * tK))
    mulUnitModule
  })))
  var expLatency = mulMatrix(0)(0)(0).latency - 1
  var fracLatency = mulMatrix(0)(0)(0).latency

  // Align Logic
  // Get Max Exp Value for each (m, n) pair.
  // Reduce Input Vec(K / tK + 1)
  val maxExp = VecInit.tabulate(M, N)((m, n) => {
    val rawExp = VecInit.tabulate(K / tK + 1)( tk => {
      Mux((tk == (K / tK)).B, cin_exp_pipe(expLatency)(m)(n), mulMatrix(m/tM)(n/tN)(tk).c_exp(m%tM)(n%tN))
    })
    rawExp.reduceTree((l, r) => {
      Mux(l > r, l, r)
    })
  })
  val diffExp = VecInit.tabulate(M, N, K / tK + 1)((m, n, tk) => {
    Mux((tk == (K / tK)).B,
      maxExp(m)(m) - cin_exp_pipe(expLatency)(m)(n),
      maxExp(m)(m) - mulMatrix(m/tM)(n/tN)(tk).c_exp(m%tM)(n%tN))
  })

  val maxExpPipe  = pipe.genPipe(RegNext(maxExp), 2)
  val diffExpPipe = pipe.genPipe(RegNext(diffExp), 1)

  val fracS0 = VecInit.tabulate(M, N, K / tK + 1)((m, n, tk) => {
    val v = Wire(SInt(wAdder.W))
    val valFrac = Mux((tk == (K / tK)).B,
      Cat(cin_frac_pipe(fracLatency)(m)(n), 0.S((wAdder - wE).W)).asSInt,
      Cat(mulMatrix(m/tM)(n/tN)(tk).c_frac(m%tM)(n%tN), 0.S((wAdder - wI).W)).asSInt
    )
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
    -fracS2(m)(n), fracS2(m)(n))(wMerge-2,0))
  val fracLZCS2 = VecInit.tabulate(M, N)((m, n) => {CLZ(fracAbsS2(m)(n))})
  val fracShiftedS2 = VecInit.tabulate(M, N)((m, n) => fracAbsS2(m)(n) << fracLZCS2(m)(n))
  val fracSignedS2 = VecInit.tabulate(M, N)((m, n) => {
    val c = Cat(0.U(1.W), fracShiftedS2(m)(n)).asSInt
    Mux(fracSignS2(m)(n), -c, c)
  })

  // Output Rounding for Exp
  val expS2 = VecInit.tabulate(M, N)((m ,n) => {
    val diff = Cat(0.U(1.W), fracLZCS2(m)(n)).asSInt - (wMerge - wAdder).S
    val curMax = Cat(0.U(1.W), maxExpPipe(2)(m)(n)).asSInt
    val tryMax = curMax -& diff
    Mux(
      tryMax(tryMax.getWidth-1).asBool, 0.U(wE), // UnderFlow
    Mux(
      tryMax(tryMax.getWidth-2).asBool, (1.U << wE).asUInt - 1.U, // OverFlow
      tryMax(wE - 1, 0)
    )
    )(wE - 1, 0)
  })

  // Output Assembling
  cout_exp  := RegNext(expS2)
  cout_frac := RegNext(VecInit.tabulate(M,N)((m,n) => fracSignedS2(m)(n)(wMerge-1, wMerge-wF)))

}
