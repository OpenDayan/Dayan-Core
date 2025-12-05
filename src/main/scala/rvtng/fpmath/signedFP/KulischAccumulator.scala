package rvtng.fpmath.signedFP

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import hardfloat._
import rvtng.fpmath.GetMaxDiffTop

case class KulischAccConfig(
  inExpWidth: Int = 8,
  inSigWidth: Int = 25,
  outExpWidth: Int = 8,
  outSigWidth: Int = 27,
  numInputs: Int = 4
)

class KulischAccumulator(c: KulischAccConfig) extends Module {
  val io = IO(new Bundle {
    // Input and Output are now SignedFP
    val in = Input(Vec(c.numInputs, new SignedFP(c.inExpWidth, c.inSigWidth)))
    // Alignment
    val out = Output(new SignedFP(c.outExpWidth, c.outSigWidth)) // 注意，输出没有做 Rounding
  })

  // 1. 对齐
  // 先获取所有指数中的最大值及每个指数的偏移值
  val expMaxDiff = Module(new GetMaxDiffTop(log2Ceil(c.numInputs), c.inExpWidth)).io
  for(i <- 0 until (1 << log2Ceil(c.numInputs))) {
    if(i < c.numInputs) {
      expMaxDiff.in(i) := io.in(i).exp
    } else {
      expMaxDiff.in(i) := (BigInt(1) << (c.inExpWidth-1)).S(c.inExpWidth.W)
    }
  }

  // 对齐后的指数位值
  val exp = expMaxDiff.max + log2Ceil(c.numInputs).S
  // 加法输出构成：log2N | inSigWidth | expand  == c.outSigWidth
  val expandWidth = c.outSigWidth - log2Ceil(c.numInputs) - c.inSigWidth
  // 加法输入构成：inSigWidth
  // 将所有输入的尾数，右移动展开到内部加法树位宽上
  val frac = Wire(Vec(c.numInputs, SInt((c.inSigWidth + expandWidth).W)))
  // 对齐内部精度
  for(i <- 0 until c.numInputs) {
    // 拓展 frac
    val raw = Cat(io.in(i).frac, 0.S(expandWidth.W)).asSInt
    // 右移 frac
    frac(i) := raw >> expMaxDiff.out_diff(i)
  }

  // 开始累加
  io.out.exp := exp
  io.out.frac := frac.reduceTree(_ +& _)
}

class KulischAccumulatorTestTop extends Module {
  // Test configuration: 4-input FP32 Accumulator
  val io = IO(new Bundle {
    val in = Input(Vec(4, UInt(32.W))) // IEEE 754 Single Precision
    val out = Output(UInt(32.W))
  })
  val dut = Module(new KulischAccumulator(KulischAccConfig(
    numInputs = 4,
    inExpWidth = 10,
    inSigWidth = 25,
    outExpWidth = 10,
    outSigWidth = 70
  )))
  dut.io.in := VecInit(io.in.map(SignedFPFrom(8, 24, _)))
  io.out := FNFromSignedFP(SignedFPNorm(dut.io.out))
}


object KulischAccumulatorTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new KulischAccumulatorTestTop(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/KulischAccumulatorTestTop"
    )
  )
}


