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

// FP8 E4M3 to FP32

package dayan.rvtng.vpu

import chisel3._
import chisel3.util._

class FP8E4M3ToFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(8.W))  
    val out = Output(UInt(32.W)) 
  })

  val sign_fp8 = io.in(7)
  val exp_fp8  = io.in(6, 3)
  val mant_fp8 = io.in(2, 0)

  val isZero        = (exp_fp8 === 0.U) && (mant_fp8 === 0.U)
  val isSubnormal   = (exp_fp8 === 0.U) && (mant_fp8 =/= 0.U)
  val isNaN         = (exp_fp8 === "b1111".U) && (mant_fp8 === "b111".U)
  val isNormal      = !isZero && !isSubnormal && !isNaN

  val sign_fp32     = sign_fp8
  val exp_fp32      = WireInit(0.U(8.W))
  val mant_fp32     = WireInit(0.U(23.W))
  when (isZero) {
    // ±0
    exp_fp32    := 0.U
    mant_fp32   := 0.U
  } .elsewhen (isSubnormal) {
    // FP8 subnormal: exponent = -6
    // FP32 exponent = -6 + 127 = 121
    switch (mant_fp8) {
      is(0.U){ 
        exp_fp32 := "b00000000".U 
        mant_fp32 := "b00000000000000000000000".U 
        }
      is(1.U) { 
        exp_fp32 := "b01110110".U 
        mant_fp32 := "b00000000000000000000000".U 
        } 
      is(2.U) { 
        exp_fp32 := "b01110111".U 
        mant_fp32 := "b00000000000000000000000".U 
        } 
      is(3.U) { 
        exp_fp32 := "b01110111".U 
        mant_fp32 := "b10000000000000000000000".U 
        } 
      is(4.U) { 
        exp_fp32 := "b01111000".U 
        mant_fp32 := "b00000000000000000000000".U 
        } 
      is(5.U) { 
        exp_fp32 := "b01111000".U 
        mant_fp32 := "b01000000000000000000000".U 
        } 
      is(6.U) { 
        exp_fp32 := "b01111000".U 
        mant_fp32 := "b10000000000000000000000".U 
        } 
      is(7.U) { 
        exp_fp32 := "b01111000".U 
        mant_fp32 := "b11000000000000000000000".U 
        } 
    }
  } .elsewhen (isNaN) {
    exp_fp32    := "b11111111".U
    mant_fp32   := "b10000000000000000000000".U   // in accord with float_to_ieee754.py
  } .otherwise {
    // Normal case (including exp==1111 and mant ≠ 111)
    // FP8 bias = 7, FP32 bias = 127 → offset = +120
    // 0000xxxx - 00000111 = yyyyyyyy - 01111111
    val exp_fp8_ = Cat(0.U(4.W), exp_fp8) // 4-bit exponent to 8-bit
    exp_fp32    := exp_fp8_ + "b01111000".U
    mant_fp32   := (mant_fp8 << 20).asUInt
  }

  io.out := Cat(sign_fp32, exp_fp32, mant_fp32)
}


// object FP8E4M3ToFP32 {
//     def apply(in: Float) = {
//         val module = Module(new FP8E4M3ToFP32())
//         module.io.in := in
//         module.io.out
//     }
// }

// object FP8E4M3ToFP32 {
//   def apply(in: Float) = {
//     if (in.expWidth == 4 && in.sigWidth == 4) {
//       val e4m3tofp32 = Module(new FP8E4M3ToFP32())
//       e4m3tofp32.io.in := in.asUInt
//       e4m3tofp32.io.out
//     } else {
//       in.asUInt 
//     }
//   }
// }

object FP8E4M3ToFP32 {
  def apply[T <: Data](in_a: T, in_c: T): T = {
    (in_a, in_c) match {
    // 检查 a 是 FP8E4M3 (exp=4, sig=4)，且 c 是 FP32 (exp=8, sig=24)
    case (aFloat: Float, cFloat: Float) 
      if (aFloat.expWidth == 4 && aFloat.sigWidth == 4 && 
         cFloat.expWidth == 8 && cFloat.sigWidth == 24) => {      
          val e4m3tofp32 = Module(new FP8E4M3ToFP32())
          e4m3tofp32.io.in := aFloat.asUInt
          e4m3tofp32.io.out.asTypeOf(in_c)
         }

    case _ => in_a
    }
  }
}