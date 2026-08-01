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

// package convertutils

// import chisel3._
// import chisel3.util._
// import rvtng.vpu.GemminiISA._
// import rvtng.vpu.Util._
// import org.chipsalliance.cde.config.Parameters
// import midas.targetutils.PerfCounter
// import freechips.rocketchip.util.SeqToAugmentedSeq
// import freechips.rocketchip.diplomacy.BufferParams.default
// import freechips.rocketchip.util.DontTouch
// import freechips.rocketchip.rocket.Util_cpu.floorAdd

// import ConvertType._
// import ConvertMode._
// import rvtng.vpu._
// import rvtng.vpu.Arithmetic._
// import rvtng.vpu.Arithmetic.FloatArithmetic._

// // class ConvertUnit[T <: Data](
// //   dataWidth: Int, val datatype: T,
// //   convertDataWidth: Int, val convertDataType: T
// // ) (implicit ev: Arithmetic[T])extends Module {
// //   import ev._
// class ConvertUnit[T <: Data](
//   dataWidth: Int, val datatype: T,
//   convertDataWidth: Int, val convertDataType: T
// ) extends Module {
//   val io = IO(new Bundle {
//     val a            = Input(datatype)
//     val mode         = Input(UInt(2.W))
//     val valid        = Input(Bool())
//     val result       = Output(convertDataType)
//     val output_valid = Output(Bool())
//   })


//   // 默认输出
//   val result = WireInit(0.U.asTypeOf(convertDataType))
//   val outputValid = WireInit(false.B)

//   // // 根据输入/输出类型例化子模块
//   // if (datatype == Float(8,24) && convertDataType == Float(8,8)) {
//   //   val cvt = Module(new FP32ToBF16)  // 纯组合逻辑
//   //   cvt.io.in := io.a.asUInt

//   //   when(io.valid) {
//   //     result := cvt.io.out.asTypeOf(convertDataType)
//   //     outputValid := true.B
//   //   }
//   // }
//   // else if (datatype == Float(8,24) && convertDataType == Float(4,4)) {
//   //   val cvt = Module(new FP32ToE4M3)  // 纯组合逻辑
//   //   cvt.io.in := io.a.asUInt

//   //   when(io.valid) {
//   //     result := cvt.io.out.asTypeOf(convertDataType)
//   //     outputValid := true.B
//   //   }
//   // }

//   // io.result       := result
//   // io.output_valid := outputValid


//   val cvt = Module(new FP32ToE4M3)  // 纯组合逻辑
//     cvt.io.in := io.a.asUInt

//   when(io.valid) {
//     result := cvt.io.out.asTypeOf(convertDataType)
//     outputValid := true.B
//   }
//   io.result       := result
//   io.output_valid := outputValid
// }
