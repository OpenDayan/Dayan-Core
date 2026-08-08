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
// import dayan.rvtng.vpu.GemminiISA._
// import dayan.rvtng.vpu.Util._
// import org.chipsalliance.cde.config.Parameters
// import midas.targetutils.PerfCounter
// import freechips.rocketchip.util.SeqToAugmentedSeq
// import freechips.rocketchip.diplomacy.BufferParams.default
// import freechips.rocketchip.util.DontTouch
// import freechips.rocketchip.rocket.Util_cpu.floorAdd

// import ConvertType._
// import ConvertMode._

// class ConvertIn[T <: Data](dataWidth: Int, datatype : T, ComputeUnitnums: Int) extends Bundle{
//   val a = Vec(ComputeUnitnums, datatype)
//   val mode = UInt(2.W)
//   // val inType = UInt(7.W)
//   // val outType = UInt(7.W)
// }

// class ConvertOut[T <: Data](dataWidth: Int, datatype : T, ComputeUnitnums: Int) extends Bundle{    
//   val result = Vec(ComputeUnitnums, datatype)
// }

// class Convert[T <: Data](dataWidth: Int, val datatype : T, convertDataWidth: Int, 
//                           val convertDataType : T, ComputeUnitnums: Int) extends Module {
//   val io = IO(new Bundle {
//     val input = Flipped(Decoupled(new ConvertIn(dataWidth, datatype, ComputeUnitnums)))
//     val output = Decoupled(new ConvertOut(convertDataWidth, convertDataType, ComputeUnitnums))
//   })

//   val result = Wire(Vec(ComputeUnitnums, convertDataType))
//   io.input.ready := true.B

//   val computeUnits = Seq.fill(ComputeUnitnums)(Module(new ConvertUnit(dataWidth, datatype, convertDataWidth, convertDataType)))
//   for (i <- 0 until ComputeUnitnums) {
//     computeUnits(i).io.a := io.input.bits.a(i).asTypeOf(datatype)
//     computeUnits(i).io.mode := io.input.bits.mode
//     // computeUnits(i).io.inType := io.input.bits.inType
//     // computeUnits(i).io.outType := io.input.bits.outType
//     computeUnits(i).io.valid := io.input.valid
//     result(i) := computeUnits(i).io.result
//   }
  
//   io.output.bits.result := result
//   io.output.valid :=  computeUnits.map(_.io.output_valid).reduce(_||_)

// }

