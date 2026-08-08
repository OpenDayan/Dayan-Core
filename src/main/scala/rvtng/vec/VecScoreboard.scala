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
import dayan.rvtng.top._
import _root_.circt.stage.ChiselStage

/*
*
* 需求:
*    将来自 threads 的多个取指令请求，汇聚为一个独立的请求。
*    同时管理块的起始与结束，负责给 CSR 提供指令
*
* 接口:
*    张量指令请求接口: 流式接口，用于接收 TCSR 读 / 写请求
*    张量指令返回接口: 流式接口，用于返回 TCSR 请求结果（如果有）
*    张量 Scoreboard 完成接口: 流式接口，用于返回 Scoreboard 事务
*
*/

class VecScoreboard(params: VecParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new VecInstWSchedule(params))))
  val instResp = IO(Decoupled(new TensorResp()))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))
  val busy = IO(Input(Bool())) // 外部有模块正忙

  val instRebuild = VecInstRebuild(instReq.bits)
  val instIsBar = instRebuild(14)

  instReq.ready := !busy & Mux(instIsBar, instResp.ready, sbComp.ready)
  instResp.valid := !busy & instReq.valid & instIsBar
  sbComp.valid := !busy & instReq.valid & !instIsBar

  instResp.bits.rd := instReq.bits.vsrc1
  instResp.bits.data := instReq.bits.rdData
  sbComp.bits.entryId := instReq.bits.rdData

}
