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

package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._


class SFU[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val opSel = Input(UInt(6.W)) // 0: recip, 1:rsqrt, 2:sin, 3:cos, 4:exp, 5:relu
    val inputValid = Input(Bool())
    val inputReady = Output(Bool())

    val result = Output(datatype)
    val outputValid = Output(Bool())
  })

  val opSelRecip :: opSelRsqrt :: opSelSin :: opSelCos :: opSelExp :: opSelRelu :: Nil = Enum(6)

  val sfuRcpMod = Module(new SFURcp(dataWidth, datatype))
  sfuRcpMod.io.a := io.a
  sfuRcpMod.io.inputValid := false.B

  val sfuInvSqrtMod = Module(new SFUInvSqrt(dataWidth, datatype))
  sfuInvSqrtMod.io.a := io.a
  sfuInvSqrtMod.io.inputValid := false.B

  val sfuSinCosMod = Module(new SFUSinCos(dataWidth, datatype))
  sfuSinCosMod.io.a := io.a
  sfuSinCosMod.io.inputValid := false.B

  val sfuFPExp = Module(new SFUFPExp(dataWidth, datatype))
  sfuFPExp.io.a := io.a
  sfuFPExp.io.inputValid := false.B

  val sfuRelu = Module(new SFURelu(dataWidth, datatype))
  sfuRelu.io.a := io.a
  sfuRelu.io.inputValid := false.B

  // io.inputReady := !sfuRcpMod.io.busy && !sfuSinCosMod.io.busy && !sfuInvSqrtMod.io.busy && !sfuFPExp.io.busy
  io.inputReady := true.B
  // val lastOpSel = RegInit(6.U)
  // when(io.inputValid && io.inputReady) {
  //   lastOpSel := io.opSel
  // }
  // io.inputReady := (lastOpSel === io.opSel) || MuxLookup(lastOpSel, true.B)(Seq(
  //   opSelRecip -> !sfuRcpMod.io.busy, // 0: recip
  //   // opSelRsqrt -> true.B, // 1: rsqrt
  //   // opSelSin -> true.B, // 2: sin
  //   // opSelCos -> true.B, // 3: cos
  //   // opSelExp -> true.B, // 4: exp
  //   // opSelRelu -> true.B // 5: relu
  // ))

  val inputFire = io.inputValid && io.inputReady
  switch(io.opSel) {
    is(opSelRecip) { sfuRcpMod.io.inputValid := inputFire } // 0: recip
    is(opSelRsqrt) { sfuInvSqrtMod.io.inputValid := inputFire } // 1: rsqrt
    is(opSelSin) { sfuSinCosMod.io.inputValid := inputFire } // 2: sin
    is(opSelCos) { sfuSinCosMod.io.inputValid := inputFire } // 3: cos
    is(opSelExp) { sfuFPExp.io.inputValid := inputFire } // 4: exp
    is(opSelRelu) {sfuRelu.io.inputValid := inputFire } // 5: relu
  }

  io.result := MuxLookup(io.opSel, 0.U.asTypeOf(datatype))(Seq(
    opSelRecip -> sfuRcpMod.io.result, // 0: recip
    opSelRsqrt -> sfuInvSqrtMod.io.result, // 1: rsqrt
    opSelSin -> sfuSinCosMod.io.resultSin, // 2: sin
    opSelCos -> sfuSinCosMod.io.resultCos, // 3: cos
    opSelExp -> sfuFPExp.io.result, // 4: exp
    opSelRelu -> sfuRelu.io.result, //0.U.asTypeOf(datatype) // 5: relu
  ))

  io.outputValid := MuxLookup(io.opSel, false.B)(Seq(
    opSelRecip -> sfuRcpMod.io.outputValid, // 0: recip
    opSelRsqrt -> sfuInvSqrtMod.io.outputValid, // 1: rsqrt
    opSelSin -> sfuSinCosMod.io.outputValid, // 2: sin
    opSelCos -> sfuSinCosMod.io.outputValid, // 3: cos
    opSelExp -> sfuFPExp.io.outputValid, // 4: exp
    opSelRelu -> sfuRelu.io.outputValid, // 5: relu
  ))
}

class VSFUIn[T <: Data](dataWidth: Int, computeUnitnums: Int) extends Bundle{
  val a = Vec(computeUnitnums, UInt(dataWidth.W))
  val opSel = UInt(6.W)
}

class VSFUOut[T <: Data](dataWidth: Int, computeUnitnums: Int) extends Bundle{    
  val result = Vec(computeUnitnums, UInt(dataWidth.W))
}

class VSFU[T <: Data](dataWidth: Int, computeUnitnums: Int) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VSFUIn(dataWidth, computeUnitnums)))

    val output = Decoupled(new VSFUOut(dataWidth, computeUnitnums))
  })

  val datatype = Float(8, 24)

  val result = Wire(Vec(computeUnitnums, datatype))

  val computeUnits = Seq.fill(computeUnitnums)(Module(new SFU(dataWidth, datatype)))
  for (i <- 0 until computeUnitnums) {
    computeUnits(i).io.a := io.input.bits.a(i).asTypeOf(datatype)
    computeUnits(i).io.opSel := io.input.bits.opSel
    computeUnits(i).io.inputValid := io.input.valid
    result(i) := computeUnits(i).io.result
  }
  
  io.input.ready := computeUnits.map(_.io.inputReady).reduce(_&&_)
  io.output.bits.result := result.asTypeOf(Vec(computeUnitnums, UInt(dataWidth.W)))
  io.output.valid :=  computeUnits.map(_.io.outputValid).reduce(_&&_)
}

