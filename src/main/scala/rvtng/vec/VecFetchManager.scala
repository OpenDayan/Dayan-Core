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
import chisel3.util.experimental.decode._
import _root_.circt.stage.ChiselStage

class VecBlockEndPtrMark(params: VecParams) extends Bundle {
  val valid = Bool()
  val ptr = UInt(params.log2MaxInstCount.W)
}

/*
* 需求:
*    将来自 threads 的多个取指令请求，汇聚为一个独立的请求。
*    同时管理块的起始与结束，负责给 CSR 提供指令
*
* 接口:
*    fetchIn: 流式输入线程取指请求组
*    instPtr: 指令指针
*    instDeq: 指令弹出数目
*    instWindows: 指令窗口
*    csrInst: 流式输出 CSR 指令
*    sbInst: 流式输出 Scoreboard 指令
*    blockStart: 脉冲输出给 TaskGen 负责任务生成
*    endMark: 缓冲块结束地址，减少取指请求数。
* */
class VecFetchManager(params: VecParams) extends Module {
  val fetchIn = IO(Vec(params.maxThreadCount, Flipped(Decoupled(UInt(params.log2MaxInstCount.W)))))
  val instPtr = IO(Output(UInt(params.log2MaxInstCount.W)))
  val instDeq = IO(Output(UInt(params.log2MaxInstCount.W)))
  val instWindows = IO(Input(new VecInstFetchResp(params)))
  val csrInst = IO(Decoupled(new VecInstWSchedule(params)))
  val sbInst  = IO(Decoupled(new VecInstWSchedule(params)))
  val blockStart = IO(Output(Bool()))
  val blockEnd = IO(Input(Bool()))
  val endMark = IO(Output(new VecBlockEndPtrMark(params)))

  // 这里不用聚合请求
  private val arb = Module(new RRArbiter(fetchIn(0).bits.cloneType, params.maxThreadCount)).io

  arb.in <> fetchIn

  // 内部记录当前状态
  private val curIsInBlock = RegInit(0.B)
  curIsInBlock := (curIsInBlock & !blockEnd) | blockStart

  // 记录首个非块内指令指针
  private val blockEndPtr = Reg(new VecBlockEndPtrMark(params))
  when(curIsInBlock & instWindows.valid & instWindows.inst.finishHint) {
    blockEndPtr.ptr := instWindows.pc
    blockEndPtr.valid := 1.B
  }
  when(!curIsInBlock) {
    blockEndPtr.valid := 0.B
  }
  arb.out.ready := 1.B

  // 管理 instPtr 和 instDeq 及 csrInst
  private val headInstVal = instWindows.valid & !RegNext(curIsInBlock)
  private val headIsBlockInst = headInstVal & !instWindows.inst.finishHint
  private val headIsImmediate = headInstVal & instWindows.inst.finishHint
  private val headIsCsr       = headInstVal & instWindows.inst.fu === VecFuEnum.Csr
  private val headIsSb        = headInstVal & instWindows.inst.fu === VecFuEnum.Scoreboard
  when(!curIsInBlock) {
    // 当前处于块外
    instPtr := Mux(headIsImmediate,
      Mux(instWindows.inst.fu === VecFuEnum.Csr, csrInst.ready, sbInst.ready).asUInt, 0.U)
    instDeq := Mux(headIsImmediate,
      Mux(instWindows.inst.fu === VecFuEnum.Csr, csrInst.ready, sbInst.ready).asUInt, 0.U)
  }.otherwise {
    // 当前处于块内
    instPtr := arb.out.bits
    instDeq := Mux(blockEnd, blockEndPtr.ptr, 0.U)
  }
  csrInst.valid := !curIsInBlock & headIsCsr
  csrInst.bits  := instWindows.inst
  sbInst.valid  := !curIsInBlock & headIsSb
  sbInst.bits   := instWindows.inst
  blockStart    := !curIsInBlock & headIsBlockInst
  endMark       := blockEndPtr
}
