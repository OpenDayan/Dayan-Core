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
import VpuIsa._
import Util._

import sincosutils._

class SFUSinCos[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val inputValid = Input(Bool())

    val resultSin = Output(datatype)
    val resultCos = Output(datatype)
    val outputValid = Output(Bool())

    val busy = Output(Bool())
  })

  val iterNum = 30
  val expWidth = 8
  val sigWidth = 24
  val inIntWidth = 2
  val inFraWidth = 29

  val sinCosMod = Module(new SinCosFN(expWidth, sigWidth, inIntWidth, inFraWidth, iterNum))
  sinCosMod.io.roundingMode := consts.round_minMag

  sinCosMod.io.in := io.a.asUInt
  io.resultSin := sinCosMod.io.out_sin.asTypeOf(datatype)
  io.resultCos := sinCosMod.io.out_cos.asTypeOf(datatype)

  // TODO
  val stageNum = iterNum + 2

  val stageValidRegs = RegInit(VecInit.fill(stageNum)(false.B))
  stageValidRegs(0) := io.inputValid
  for (i <- 1 until stageValidRegs.length) {
    stageValidRegs(i) := stageValidRegs(i - 1)
  }

  // io.outputValid := ShiftRegister(io.inputValid, stageNum, true.B)
  io.outputValid := stageValidRegs.last
  io.busy := stageValidRegs.reduce(_||_)
}