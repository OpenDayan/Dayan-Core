/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package dayan.rvtng.fpmath.signedFP

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import hardfloat._
import dayan.rvtng.fpmath.GetMaxDiffTop

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
  // TODO: Use CSA73 CSA53 CSA32 Here to speedup.
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
    inExpWidth = 9,
    inSigWidth = 25,
    outExpWidth = 10,
    outSigWidth = 70
  )))
  dut.io.in := VecInit(io.in.map(SignedFPFrom(9, 25, _)))
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


