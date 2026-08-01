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

package rvtng.vec

import chisel3._
import chisel3.util._

/*
* 需求:
*    将来自 TaskGen 的单个 Task，散发到 N 个 thread 中的一个。
*
* 接口:
*    taskIn: 流式输入任务
*    taskOut: 多组流式输出任务请求，接入到各个 thread。
* */
class VecTaskDispatch(params: VecParams) extends Module {
  val taskIn  = IO(Flipped(Decoupled(new VecInitTaskGroup(params))))
  val taskOut = IO(Vec(params.maxThreadCount, Decoupled(new VecInitTaskGroup(params))))

  private val taskQ = Queue(taskIn, 2, false, false)


  // Reshuffle Index for better scheduling
  private def shuffleIdx(in: Int): Int = {
    (in / (params.maxThreadCount / 4)) + 4 * (in % (params.maxThreadCount / 4))
  }

  for(i <- 1 until params.maxThreadCount) {
    val idx = shuffleIdx(i)
    val beforeRdy = VecInit.tabulate(i)(j => {
      val jIdx = shuffleIdx(j)
      taskOut(jIdx).ready
    }).asUInt.orR
    // 如若排在本请求之前的所有端口均未就绪，则本端口用于发射指令。
    taskOut(idx).valid := taskQ.valid & !beforeRdy
  }
  taskOut(0).valid := taskQ.valid
  taskQ.ready := VecInit.tabulate(params.maxThreadCount)(j => {
    taskOut(j).bits := taskQ.bits
    taskOut(j).ready
  }).asUInt.orR

  val busy = IO(Output(Bool()))
  busy := taskQ.valid // TaskQ Not Empty yet
}
