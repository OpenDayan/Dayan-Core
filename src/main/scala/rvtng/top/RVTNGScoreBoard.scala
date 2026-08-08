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

package dayan.rvtng.top

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class RVTNGScoreBoard(queueSz: Int = 8, N: Int = 3) extends Module {
  val sbComp  = IO(Vec(N, Flipped(Decoupled(new ScoreboardComp()))))
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))

  private val Q = VecInit(sbComp.map(Queue(_, queueSz)))

  private val arb = Module(new RRArbiter(sbComp(0).bits.cloneType, N)).io
  arb.in <> Q

  instResp.bits.rd   := instReq.bits.instWord(11, 7)
  instResp.bits.data := Mux(arb.out.valid, arb.out.bits.entryId, 0.U)
  instResp.valid := instReq.valid
  instReq.ready  := instResp.ready
  arb.out.ready := instResp.fire
}
