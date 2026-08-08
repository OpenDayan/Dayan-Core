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

package dayan.rvtng.vec

import chisel3._
import chisel3.util._
import dayan.rvtng.utils.PartialAssign

/*
* 需求：
*    本模块实现 Int 数据的 Function Unit，输入来自 Regfile，并输出到 Regfile。
* 由于所有 Int 指令均为静态调度（Counter Based），因此本模块不需要知会 GlobalScheduler 任何调度相关的信息。
*
* 接口：
*    inst:  带有数据的指令流 VecInstRData, 注意检查该接口不存在 deny。
*    wback: 写回请求，无阻塞接口
*
* */
class VecFUInt(params: VecParams) extends Module {
  val inst = IO(Flipped(Decoupled(new VecInstRData(params))))
  private val lowHalf = RegNext(inst.fire) // s1
  private val highHalf = RegNext(lowHalf)  // s2
  private val outVld = RegNext(highHalf)   // s3
  inst.ready := !lowHalf
  private val in1 = RegEnable(inst.bits.vsrc1Data, inst.fire)
  private val in2 = RegEnable(inst.bits.vsrc2Data, inst.fire)

  private val s0Ctrl = Wire(new VecInstWThreadInfo(params))
  s0Ctrl := inst.bits
  private val s1Ctrl = RegEnable(s0Ctrl, inst.fire)
  private val s2Ctrl = RegEnable(s1Ctrl, lowHalf)
  private val s3Ctrl = RegEnable(s2Ctrl, highHalf)

  private val halfIn1 = Mux(lowHalf,
    VecInit.tabulate(params.laneWidth / 2)(i => {
      in1(i)
    }),
    VecInit.tabulate(params.laneWidth / 2)(i => {
      in1(i + (params.laneWidth / 2))
    })
  )

  private val halfIn2 = Mux(lowHalf,
    VecInit.tabulate(params.laneWidth / 2)(i => {
      in2(i)
    }),
    VecInit.tabulate(params.laneWidth / 2)(i => {
      in2(i + (params.laneWidth / 2))
    })
  )

  // TODO: 更换使用真实 ALU，支持所有 Int 指令。
  private val halfResult = VecInit(halfIn1.zip(halfIn2).map(it => {
    it._1 + it._2
  }))

  private val result = Reg(Vec(params.laneWidth, UInt(32.W)))

  for(i <- 0 until params.laneWidth / 2) {
    when(lowHalf) {
      result(i) := halfResult(i)
    }
    when(highHalf) {
      result(i + params.laneWidth / 2) := halfResult(i)
    }
  }

  val wback = IO(Output(new VecWData(params)))
  wback.valid := outVld
  wback.wAddr := Cat(s3Ctrl.htId, s3Ctrl.inst.realVDst)
  wback.wData := result
  wback.htId  := s3Ctrl.htId
}
