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

package dayan.rvtng.vpu

import chisel3._
import chisel3.util._

import Util._


/* Handle the broadcasting logic in the column direction. */
class ColumnBroadcaster[T <: Data](dataWidth: Int, val datatype : T, ComputeUnitnums: Int) extends Module {
  val io = IO(new Bundle {
    val data_row = Input(Vec(ComputeUnitnums, datatype))
    val broadcastDim = Input(UInt(4.W))
    
    val processed_data = Output(Vec(ComputeUnitnums, datatype))
  })
  
  
  // 处理广播逻辑
  val processed_data = WireInit(io.data_row)

  when(io.broadcastDim(0) === 1.U){
    for (i <- 0 until ComputeUnitnums) {
          processed_data(i) := io.data_row(0)
      }
  }
  
  io.processed_data := processed_data
}

/* Integer variable calculation unit  */
class ALUIntUnit[T <: Data](dataWidth: Int, val datatype : T) (implicit ev: Arithmetic[T])extends Module {
  import  ev._
  val io = IO(new Bundle {
    val a = Input(datatype)
    val b = Input(datatype)
    val opSel = Input(UInt(6.W)) // 0: add, 1:sub, 2:mul, 3: srl, 4:sra, 5:sll  
    val valid = Input(Bool())
    
    val result = Output(datatype)
    val output_valid = Output(Bool())
  })
  
  // 操作选择
  val addResult = io.a + io.b
  val subResult = io.a - io.b
  val mulResult = io.a * io.b

  val shiftOp = io.a.asUInt
  val shiftWidth = io.b.asUInt(4,0)
  val srlResult = (shiftOp >> shiftWidth)(31,0).asTypeOf(datatype)
  val sraResult = (shiftOp.asSInt >> shiftWidth)(31,0).asTypeOf(datatype)
  val sllResult = (shiftOp << shiftWidth)(31,0).asTypeOf(datatype)    // 最简单的写法，没有资源复用

  val andResult = (io.a.asUInt & io.b.asUInt).asTypeOf(datatype)
  val orResult  = (io.a.asUInt | io.b.asUInt).asTypeOf(datatype)
  val xorResult = (io.a.asUInt ^ io.b.asUInt).asTypeOf(datatype)
  
  // 结果选择
  val result = WireInit(0.U.asTypeOf(datatype))
  
  when(io.valid) {
    switch(io.opSel) {
      is("b000000".U) { result := addResult }  // 加法
      is("b000001".U) { result := subResult }  // 减法
      is("b000010".U) { result := mulResult }  // 乘法（取低位）
      is("b000011".U) { result := srlResult }  // 逻辑右移
      is("b000100".U) { result := sraResult }  // 算数右移
      is("b000101".U) { result := sllResult }  // 逻辑左移
      // lutop
      is("b011000".U) { result := andResult }  // bitwise AND
      is("b011110".U) { result := orResult  }  // bitwise OR
      is("b010110".U) { result := xorResult }  // bitwise XOR
    }
  }.otherwise {
    result := 0.U.asTypeOf(datatype)
  }
  
  io.result := RegNext(result)
  io.output_valid := RegNext(io.valid)
}

class ElewiseOpIn[T <: Data](dataWidth: Int, datatype : T, ComputeUnitnums: Int) extends Bundle{
  val a = Vec(ComputeUnitnums, datatype)
  val b = Vec(ComputeUnitnums, datatype)
  val opSel = UInt(6.W)
  val broadcastDim = UInt(4.W)
}

class ElewiseOpOut[T <: Data](dataWidth: Int, datatype : T, ComputeUnitnums: Int) extends Bundle{    
  val result = Vec(ComputeUnitnums, datatype)
}

/* handle Integer operation in elementwise inst */
class ElewiseIntOp[T <: Data](dataWidth: Int, val datatype : T, ComputeUnitnums: Int) (implicit ev: Arithmetic[T])extends Module {
    val io = IO(new Bundle {
        val input = Flipped(Decoupled(new ElewiseOpIn(dataWidth, datatype, ComputeUnitnums)))

        val output = Decoupled(new ElewiseOpOut(dataWidth, datatype, ComputeUnitnums))
    })

    val result = Wire(Vec(ComputeUnitnums, datatype))

    val broadcaster = Module(new ColumnBroadcaster(dataWidth, datatype, ComputeUnitnums))
    broadcaster.io.data_row := io.input.bits.b
    broadcaster.io.broadcastDim := io.input.bits.broadcastDim
    io.input.ready := true.B
  
    // 计算单元
    val computeUnits = Seq.fill(ComputeUnitnums)(Module(new ALUIntUnit(dataWidth, datatype)))
    for (i <- 0 until ComputeUnitnums) {
        computeUnits(i).io.a := io.input.bits.a(i).asTypeOf(datatype)
        computeUnits(i).io.b := broadcaster.io.processed_data(i).asTypeOf(datatype)
        computeUnits(i).io.opSel := io.input.bits.opSel
        computeUnits(i).io.valid := io.input.valid
    }

    
    for (i <- 0 until ComputeUnitnums) {
        result(i) := computeUnits(i).io.result
    }
    io.output.bits.result := result
    io.output.valid :=  computeUnits.map(_.io.output_valid).reduce(_||_)
}