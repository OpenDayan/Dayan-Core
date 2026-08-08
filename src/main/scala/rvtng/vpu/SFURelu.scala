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
import GemminiISA._
import Util._


class SFURelu[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val inputValid = Input(Bool())

    val result = Output(datatype)
    val outputValid = Output(Bool())

    val busy = Output(Bool())
  })

  //val fpReluMod = Module(new FPRelu)

  //fpReluMod.io.X := io.a.asUInt
  //io.result := fpReluMod.io.R.asTypeOf(datatype)
  
  val relu = Mux(io.a.asUInt(dataWidth-1), 0.U(32.W).asTypeOf(datatype), io.a)
  // TODO
  val stageNum = 2 

  val stageValid = Wire(Vec(stageNum, Bool()))
  stageValid(0) := RegNext(io.inputValid)
  for (i <- 1 until stageValid.length) {
    stageValid(i) := RegNext(stageValid(i - 1))
  }
  
  val stageRelu = Wire(Vec(stageNum, datatype))
  stageRelu(0) := RegNext(relu)
  for (i <- 1 until stageValid.length) {
    stageRelu(i) := RegNext(stageRelu(i-1))
  }
  


  // io.outputValid := ShiftRegister(io.inputValid, stageNum, true.B)
  io.outputValid := stageValid.last
  io.busy := stageValid.reduce(_||_)
  io.result := stageRelu.last
  when (io.outputValid) {
    printf("[SFURelu] result=0x%x\n", io.result.asUInt)
  }
}
