package rvtng.neomat

import chisel3._
import chisel3.util._
import hardfloat._
import _root_.circt.stage.ChiselStage

case class KulischAccConfig(
  inExpWidth: Int,
  inSigWidth: Int,
  outExpWidth: Int,
  outSigWidth: Int,
  numInputs: Int,
  // Optional manual override for accumulator width
  accWidth: Option[Int] = None
)

// 递归的获取所有输入中的最大值，及输入距离最大值的差值
class GetMaxDiff(log2N: Int, w: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(1 << log2N, SInt(w.W)))
    val out_diff = Output(Vec(1 << log2N, UInt(w.W)))
    val max  = Output(SInt(w.W))
    val max_diff = Input(UInt(w.W))
  })
  //         --------------------------------|
  //         ^                               |
  // in -> diff -> max -> | external | -> max_diff -> in_diff
  if(log2N == 0) {
    io.max := io.in(0)
    io.out_diff(0) := io.max_diff
  } else {
    val l = Module(new GetMaxDiff(log2N - 1, w))
    val r = Module(new GetMaxDiff(log2N - 1, w))
    l.io.in := VecInit.tabulate(1 << (log2N - 1))(i => io.in(i))
    r.io.in := VecInit.tabulate(1 << (log2N - 1))(i => io.in((1 << (log2N - 1)) + i))
    for(i <- 0 until (1 << log2N - 1)) {
      io.out_diff(i) := l.io.out_diff(i)
      io.out_diff((1 << (log2N - 1)) + i) := r.io.out_diff(i)
    }
    val local_l_sub_r = l.io.max -& r.io.max
    val local_r_sub_l = r.io.max -& l.io.max
    val local_max = Mux(local_l_sub_r(w), r.io.max, l.io.max)
    io.max := local_max
    l.io.max_diff := io.max_diff + Mux(local_r_sub_l(w), 0.U, local_r_sub_l(w-1,0))
    r.io.max_diff := io.max_diff + Mux(local_l_sub_r(w), 0.U, local_l_sub_r(w-1,0))
  }
}
class GetMaxDiffTop(log2N: Int, w: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(1 << log2N, SInt(w.W)))
    val out_diff = Output(Vec(1 << log2N, UInt(w.W)))
    val max  = Output(SInt(w.W))
  })
  val core = Module(new GetMaxDiff(3, 32))
  core.io.in := io.in
  core.io.max_diff := 0.U
  io.out_diff := core.io.out_diff
  io.max := core.io.max
}

class KulischAccumulator(c: KulischAccConfig) extends Module {
  val io = IO(new Bundle {
    // Input and Output are now RawFloat
    val in = Input(Vec(c.numInputs, new RawFloat(c.inExpWidth, c.inSigWidth)))
    // Alignment
    val out = Output(new RawFloat(c.outExpWidth, c.outSigWidth)) // 注意，输出没有做 Rounding
  })

  // 1. 对齐
  // 先获取所有指数中的最大值及每个指数的偏移值
  val expMaxDiff = Module(new GetMaxDiffTop(log2Ceil(c.numInputs), c.inExpWidth)).io
  for(i <- 0 until (1 << log2Ceil(c.numInputs))) {
    if(i < c.numInputs) {
      expMaxDiff.in(i) := io.in(i).sExp
    } else {
      expMaxDiff.in(i) := (BigInt(1) << (c.inExpWidth-1)).S(c.inExpWidth.W)
    }
  }
  // 将所有输入的尾数，右移动展开到内部加法树位宽上
  // 加法输出构成：log2N | sign | inSigWidth | expand  == c.outSigWidth
  // 加法输入构成：

  // 开始累加，累加前先对齐内部精度

}

object GetMaxDiffTop extends App {
  ChiselStage.emitSystemVerilog(
    new GetMaxDiffTop(3, 8),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/GetMaxDiffTop"
    )
  )
}
