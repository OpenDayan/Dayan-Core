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

import Util._

import hardfloat._
import consts._

class ReduceAddUnit[T <: Data](val VecSize: Int, val datatype: T) (implicit ev: Arithmetic[T]) extends Module {
  import ev._
  val io = IO(new Bundle {
    val vecIn = Input(Vec(VecSize, datatype))
    val result = Output(datatype)
  })

  def tree(inputs: Seq[T]): T = {
    if (inputs.length == 1) {
      inputs.head
    } else {
      val nextLevelInputs = inputs.grouped(2).map {
        case Seq(a, b) => {
          a + b
        }
        case Seq(a) => {
          a
        }
      }.toSeq
      tree(nextLevelInputs)
    }
  }
  io.result := tree(io.vecIn)
}

class ReduceCmpUnit[T <: Data](val VecSize: Int, val datatype: T) (implicit ev: Arithmetic[T]) extends Module {
  import ev._
  val io = IO(new Bundle {
    val vecIn = Input(Vec(VecSize, datatype))
    val opSel = Input(UInt(6.W)) // 0=Max, 1=Min
    val result = Output(datatype)
  })

  def tree(inputs: Seq[T]): T = {
    if (inputs.length == 1) {
      RegNext(inputs.head)
    } else {
      val nextLevelInputs = inputs.grouped(2).map {
        case Seq(a, b) => {
          val a_reg = RegNext(a)
          val b_reg = RegNext(b)
          val cmp_value = Reg(Bool())
          cmp_value := a > b
          Mux(io.opSel === 0.U, 
              Mux(cmp_value, a_reg, b_reg),
              Mux(cmp_value, b_reg, a_reg)
            )
        }
        case Seq(a) => {
          RegNext(a)
        }
      }.toSeq

      tree(nextLevelInputs)
    }
  }
  io.result := tree(io.vecIn)
}

class ReduceOpIn[T <: Data](dataWidth: Int, datatype: T, vecSize: Int) extends Bundle {
  val vecIn = Vec(vecSize, datatype)
  val opSel = UInt(6.W) // ReduceOp: Max, Min, Add
  val first_time = Bool()
  val last_time = Bool()
}

class ReduceOpOut[T <: Data](dataWidth: Int, datatype: T) extends Bundle {
  val result = datatype
  val last_time = Bool()
}

class ReduceFpOp[T <: Data](dataWidth: Int, val datatype: T, vecSize: Int, fpLatency: Int = 4)
                           (implicit ev: Arithmetic[T]) extends Module {
  import ev._
  
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new ReduceOpIn(dataWidth, datatype, vecSize)))
    val output = Decoupled(new ReduceOpOut(dataWidth, datatype))
  })

  val is_cmp = io.input.bits.opSel =/= 2.U
  val accumReg = RegInit(ev.cast(datatype).zero)

  val reduceAddUnit = Module(new ReduceAddUnit(vecSize, datatype))
  reduceAddUnit.io.vecIn := io.input.bits.vecIn

  val reduceCmpUnit = Module(new ReduceCmpUnit(vecSize, datatype))
  reduceCmpUnit.io.vecIn := io.input.bits.vecIn
  reduceCmpUnit.io.opSel := io.input.bits.opSel

  val Add_delay = 4 * log2Ceil(vecSize) + 4 // 3*(log2(vecSize) + 1) + 4 三级加法树延迟+与上周期结果累加延迟
  val add_valid = RegInit(VecInit(Seq.fill(Add_delay)(false.B)))
  add_valid(0) := io.input.fire && !is_cmp
  for (i <- 1 until Add_delay) {
    add_valid(i) := add_valid(i-1)
  }

  val add_last_time = RegInit(VecInit(Seq.fill(Add_delay)(false.B)))
  add_last_time(0) := io.input.bits.last_time && !is_cmp && io.input.fire
  for (i <- 1 until Add_delay) {
    add_last_time(i) := add_last_time(i-1)
  }

  val add_first_time = RegInit(VecInit(Seq.fill(Add_delay)(false.B)))
  add_first_time(0) := io.input.bits.first_time && io.input.fire
  for (i <- 1 until Add_delay) {
    add_first_time(i) := add_first_time(i-1)
  }

  val delay_add_result = RegInit(VecInit(Seq.fill(4)(ev.cast(datatype).zero))) //等待需要累加的结果产生
  
  delay_add_result(0) := reduceAddUnit.io.result
  for (i <- 1 until 4) {
    delay_add_result(i) := delay_add_result(i-1)
  }

  val add_result = Mux(add_first_time.last,
    delay_add_result.last,
    accumReg + reduceAddUnit.io.result
  )

  val cmp_delay = log2Ceil(vecSize) + 1
  val cmp_first_time = ShiftRegister(io.input.bits.first_time && io.input.fire, cmp_delay)
  val cmp_last_time = ShiftRegister(io.input.bits.last_time && is_cmp && io.input.fire, cmp_delay)
  val cmp_opsel = ShiftRegister(io.input.bits.opSel, cmp_delay)
  val cmp_valid = ShiftRegister(io.input.fire && is_cmp, cmp_delay)
  val last_cmp_result = reduceCmpUnit.io.result > accumReg
  val cmp_result = Mux(cmp_first_time,
    reduceCmpUnit.io.result,
    Mux(cmp_opsel === 0.U,
      Mux(last_cmp_result, reduceCmpUnit.io.result, accumReg),
      Mux(last_cmp_result, accumReg, reduceCmpUnit.io.result)
    )
  )

  val output_valid = (add_valid.last && !is_cmp) || (cmp_valid && is_cmp)
  val output_last_time = (add_last_time.last && !is_cmp) || (cmp_last_time && is_cmp)

  val result = Mux(is_cmp,
    cmp_result,
    add_result
  )

  when(output_valid) {
    accumReg := result
  }

  val busy = RegInit(0.U(3.W))
  when(io.input.fire) {
    busy := Mux(is_cmp, 1.U, 4.U) // 加法时，需要4个周期气泡处理数据冒险
  } .elsewhen(busy =/= 0.U) {
    busy := busy - 1.U
  }

  io.input.ready := busy === 0.U
  io.output.valid := output_valid
  io.output.bits.result := result
  io.output.bits.last_time := output_last_time
}
