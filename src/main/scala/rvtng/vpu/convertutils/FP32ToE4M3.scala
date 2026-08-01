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


// TODO: rounding mode 
class FP32ToE4M3 extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(32.W))
    val mode = Input(UInt(2.W))  // 0: round, 1: floor, 2: ceil
    val out = Output(UInt(8.W))
  })

  val sign_fp32 = io.in(31)
  val exp_fp32  = io.in(30, 23)
  val mant_fp32 = io.in(22, 0)

  // val isZero  = (exp_fp32 === 0.U) && (mant_fp32 === 0.U)
  
  val isOverflow = ( io.in(30, 20) >= 1087.U) // >448.0

  val isUnderflow = ( exp_fp32 < 118.U )  // <2^(-9)

  val isSubnormal = ( exp_fp32 < 121.U && exp_fp32 >= 118.U ) // <2^(-7) && >=2^(-9)

  val sign_fp8    = sign_fp32
  val result_fp8 = WireDefault(0.U(7.W))


  when ( isUnderflow) {
    result_fp8 := 0.U 
  // } .elsewhen (isNaN || isInf || isOverflow ) {
  } .elsewhen ( isOverflow) {
    // NaN or Inf or overflow -> set to NaN in FP8
    result_fp8 := "b1111111".U // NaN in FP8
  } .elsewhen ( isSubnormal) {
    
    // val shift = 141.U - exp_fp32.asUInt
    // val tmp = Cat(1.U(1.W), mant_fp32) >> shift    
    // result_fp8 := tmp(6, 0)

    switch (exp_fp32) {
      is(118.U) { // 2^(-9)   shift = 141 - 118 = 23
        val guard = mant_fp32(22)
        val round = mant_fp32(21)
        val sticky = mant_fp32(20, 0).orR
        val carry = MuxCase(false.B, Seq(
          (io.mode === 0.U) -> ( guard     && (round || sticky)), // round
          (io.mode === 1.U) -> ( sign_fp32 && (round || sticky)), // floor
          (io.mode === 2.U) -> (!sign_fp32 && (round || sticky))  // ceil
        ))
        result_fp8 := 1.U(7.W) + carry.asUInt
      }
      is(119.U) { // 2^(-8)   shift = 141 - 119 = 22
        val guard = mant_fp32(21)
        val round = mant_fp32(20)
        val sticky = mant_fp32(19, 0).orR
        val carry = MuxCase(false.B, Seq(
          (io.mode === 0.U) -> ( guard     && (round || sticky)), // round
          (io.mode === 1.U) -> ( sign_fp32 && (round || sticky)), // floor
          (io.mode === 2.U) -> (!sign_fp32 && (round || sticky))  // ceil
        ))
        result_fp8 := Cat(1.U(6.W), mant_fp32(22)) + carry.asUInt       
      }
      is(120.U) { // 2^(-7)   shift = 141 - 120 = 21
        val guard = mant_fp32(20)
        val round = mant_fp32(19)
        val sticky = mant_fp32(18, 0).orR
        val carry = MuxCase(false.B, Seq(
          (io.mode === 0.U) -> ( guard     && (round || sticky)), // round
          (io.mode === 1.U) -> ( sign_fp32 && (round || sticky)), // floor
          (io.mode === 2.U) -> (!sign_fp32 && (round || sticky))  // ceil
        ))
        result_fp8 := Cat(1.U(5.W), mant_fp32(22, 21)) + carry.asUInt
      }
    }

  } .otherwise {
    // normal number

    val mant_odd = io.in(20)
    val tmp = WireDefault(0.U(32.W))
    tmp := Cat(0.U, (exp_fp32.asSInt - 120.S).asUInt, mant_fp32) + "h7FFFF".U(32.W) + mant_odd.asUInt
    // val result = tmp + mant_odd.asUInt
    result_fp8 := tmp(26, 20) 

  }

  io.out := Cat(sign_fp8, result_fp8)
}




// object FP32ToFP8E4M3 {
//   def apply[T <: Data](in_a: T, in_c: T): T = {
//     (in_a, in_c) match {
//       case (aFloat: Float, cFloat: Float)
//         if aFloat.expWidth == 8 && aFloat.sigWidth == 24 &&
//            cFloat.expWidth == 4 && cFloat.sigWidth == 4 => {
//         val fp32_to_e4m3 = Module(new FP32ToFP8E4M3())
//         fp32_to_e4m3.io.in := aFloat.asUInt
//         fp32_to_e4m3.io.out.asTypeOf(in_c)
//       }

//       case _ => in_a
//     }
//   }