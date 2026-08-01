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

package convertutils

import chisel3._
import chisel3.util._

class FP32ToBF16 extends Module {
  val io = IO(new Bundle {
    val in   = Input(UInt(32.W))
    val mode = Input(UInt(2.W)) // 0: round, 1: floor, 2: ceil
    val out  = Output(UInt(16.W))
  })

  val sign     = io.in(31)
  val exponent = io.in(30, 23)
  val mantissa = io.in(22, 0)

  val isInfOrNaN = exponent === "hFF".U
  val isZero     = exponent === 0.U

  val truncated = io.in(31, 16) 
  val round_bit = io.in(15)
  val sticky_bits = io.in(14, 0).orR

  val truncated_next = Wire(UInt(17.W)) 

  val doRound = WireDefault(false.B)

  switch(io.mode) {
    is(0.U) { // round-to-nearest-even
      val lsb = truncated(0)
      doRound := round_bit && (sticky_bits || lsb)
    }
    is(1.U) { // floor
      doRound := sign && (round_bit || sticky_bits)
    }
    is(2.U) { // ceil
      doRound := !sign && (round_bit || sticky_bits)
    }
  }

  when(doRound) {
    truncated_next := Cat(0.U(1.W), truncated) + 1.U
  } .otherwise {
    truncated_next := Cat(0.U(1.W), truncated)
  }

  // -----------------
  // 处理特殊情况
  // -----------------
  val result = Wire(UInt(16.W))

  // 检查阶码是否溢出到Inf
  val isInfAfterRound = (truncated_next(15, 7) === "hFF".U) && !isInfOrNaN

  when(isInfOrNaN) {
    // Inf / NaN：尾数全1 (0x7F)
    result := Cat(truncated(15, 7), "h7F".U(7.W))
  }.elsewhen(isZero) {
    // Zero：仅保留符号
    result := Cat(sign, 0.U(15.W))
  }.elsewhen(isInfAfterRound) {
    // 溢出 -> Inf
    result := Cat(sign, "hFF".U(8.W), 0.U(7.W))
  }.otherwise {
    result := truncated_next(15, 0)
  }

  io.out := result
}