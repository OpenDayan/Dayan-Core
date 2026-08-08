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

package dayan.rvtng.dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import dayan.rvtng.top.{TensorInst, TensorResp, ScoreboardComp}

/*
* 需求：
*    本模块作为 DMA 发射队列，用于暂存所有 DMA 指令。同时为了支持同步类指令的执行（ScoreBoard / Barrier），本模块记录已发射指令数及已提交指令数。
*    所有指令在 DMA 子系统中，顺序发射，乱序执行，顺序提交。
*
* 接口：
*    输入指令流接口：输入指令流，来自标量控制 CPU。
*    TaskSpilt 输出接口：输出指令给 TaskSpilt 子模块
*    CSR 输出接口：输出指令给 CSR 子模块
*    指令完成接口：来自 WriterPublic 的指令完成脉冲信号
*    Scoreboard 接口：输出到 Scoreboard 的 Scoreboard 更新信号
*
* 实现：
*    内部为一个队列，并对队头指令做简单解码分发，完成指令内部提交到完成队列，并记录当前已发射指令数。
* 完成队列项数不为 1，但对于连续的两条同步指令，为了避免死锁，其执行效果为阻塞。
*
* 关于使用循环式有限位数时间戳用于比较时间先后的问题：
*    理论分析可验证：若存在先验，两个时间戳间隔“小于等于” 2^N，则使用 N+1 位计数器足够区别所有情况下，两个时间戳的先后顺序。
* 对于本模块，两个时间戳之间的最大间隔，为 maxOutStanding * 2 + MergeQueueSize + TransposeBufferLatency + ... + completionQueueSize
* 由于 maxOutStanding 给定值很大（512），可认为，最大间隔一定小于 maxOutStanding * 4 == 2048。因此，使用 12 位计数器标记 Issued / Commited Inst Count 即可。
*
*    在比较两个 Issue、Commit 两个时间戳先后时，先比较最高位。
*    若最高位相同，则若 Commit 低位大于等于 Issue 低位时，Commit 晚于 Issue，相关指令已提交。
*    若最高位不同，则若 Commit 低位严格小于 Issue 低位时，Commit 晚于 Issue，相关指令已提交。
*
* 关于性能计数器（TODO）：后续可在此模块中引入 PMU，记录提交指令信息用于性能分析。
*
* */

class DMAIssueQueue(params: DMAParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))

  val taskSpiltReq = IO(Decoupled(new TensorInst()))
  val csrReq  = IO(Decoupled(new TensorInst()))
  val csrResp = IO(Flipped(Decoupled(new TensorResp())))
  val instFinish = IO(Input(Bool()))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  private val compReq = Wire(DecoupledIO(new TensorInst()))
  private val compResp = Wire(DecoupledIO(new TensorResp()))

  private val respArb = Module(new RRArbiter(instResp.bits.cloneType, 2)).io
  instResp <> respArb.out
  respArb.in(0) <> csrResp
  respArb.in(1) <> compResp

  taskSpiltReq.bits := instReq.bits
  csrReq.bits := instReq.bits
  compReq.bits := instReq.bits

  private val decodeTaskSpilt = instReq.bits.instWord(31, 29) === 0b001.U
  private val decodeCsrReq    = instReq.bits.instWord(31, 27) === 0b00000.U
  private val decodeCompReq   = instReq.bits.instWord(31, 27) === 0b00001.U
  private val decodeIllegal   = !(decodeTaskSpilt | decodeCsrReq | decodeCompReq)

  taskSpiltReq.valid := instReq.valid & decodeTaskSpilt
  csrReq.valid       := instReq.valid & decodeCsrReq
  compReq.valid      := instReq.valid & decodeCompReq // Produce or Bar

  instReq.ready := (decodeTaskSpilt & taskSpiltReq.ready) |
                   (decodeCsrReq    & csrReq.ready) |
                   (decodeCompReq   & compReq.ready) | decodeIllegal

  chisel3.assert(!(instReq.valid & decodeIllegal))

  private class scoreboardInfo extends Bundle {
    val isBar = Bool()
    val rd = UInt(5.W)
    val rdData = UInt(64.W)
    val id = UInt((params.log2MaxOutStanding + 2).W)
  }

  // Inner Counter
  private val isCounter = RegInit(0.U((params.log2MaxOutStanding + 2).W))
  private val cmCounter = RegInit(0.U((params.log2MaxOutStanding + 2).W))

  // TODO: Queue Size
  // May Fail?
  private val compQ = Module(new Queue(new scoreboardInfo, 8, false, false)).io
  compQ.enq.bits.isBar  := compReq.bits.instWord(14)
  compQ.enq.bits.rd     := compReq.bits.instWord(11, 7)
  compQ.enq.bits.rdData := compReq.bits.rdData
  compQ.enq.bits.id     := isCounter
  compQ.enq.valid := compReq.valid
  compReq.ready := compQ.enq.ready

  when(taskSpiltReq.fire) {
    isCounter := isCounter + 1.U
  }
  when(instFinish) {
    cmCounter := cmCounter + 1.U
  }

  // Dequeue and completion logic
  private val lowBitsCmp  = cmCounter(params.log2MaxOutStanding, 0) >= compQ.deq.bits.id(params.log2MaxOutStanding, 0)
  private val highBitsCmp = cmCounter(params.log2MaxOutStanding + 1) === compQ.deq.bits.id(params.log2MaxOutStanding + 1)
  private val hasCommit = Mux(highBitsCmp, lowBitsCmp, !lowBitsCmp)
  sbComp.bits.entryId := compQ.deq.bits.rdData
  sbComp.valid := hasCommit & compQ.deq.valid & !compQ.deq.bits.isBar
  compResp.bits.data := compQ.deq.bits.rdData
  compResp.bits.rd := compQ.deq.bits.rd
  compResp.valid := hasCommit & compQ.deq.valid & compQ.deq.bits.isBar
  compQ.deq.ready := hasCommit & Mux(compQ.deq.bits.isBar, compResp.ready, sbComp.ready)
}
