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
* 需求：
*    作为全局调度器，输入 N 组线程请求，经过两周期调度抉择出一个就绪线程发射执行。
*
* 接口：
*    N * preIssue: N 组流式输入接口，接收来自线程控制模块的预发射指令。
*    preIssueResp: 对外脉冲接口，广播实际发射的线程信息。
*    issue: 对外指令接口，将指令送入 RF 获取向量数据
*    lsuCreditGain: 外部脉冲输入接口，增加一个 LSU Credit
*
* 实现：
*    RVTNG 中的向量部分，采用两级调度策略。第一级调度实现在每线程模块中，检查数据依赖问题，当数据依赖全部被解决之后，指令进入 PreIssue 阶段。
* 指令被 PreIssue 选中后，进入第二阶段调度，检查结构冲突问题（Register Bank Conflict; FU Conflict）
*    关于 Register Bank Conflict，本模块采用预约表的形式解决相关冲突。预约表是双 Bank 的两位移位计数器，记录双 Bank Register File 之后两个周期的预约情况，仅发射满足条件的指令。
*    关于 FU Conflict 的解决，对于固定吞吐 FU 采用计数器；对于可变吞吐 FU （LSU）采用 Credit 解决，仅在信用点充足时发射。目标 FU 请求队列弹出后，反压回填 Credit。
*
*/
class VecGlobalScheduler(params: VecParams) extends Module{
  val preIssue = IO(Vec(params.maxThreadCount, Flipped(Decoupled(new VecInstWThreadInfo(params)))))
  val preIssueResp = IO(Output(new VecSlotInfo(params)))
  val issue = IO(Decoupled(new VecInstWThreadInfo(params)))
  val lsuCreditGain = IO(Input(Bool()))

  // Read RegisterFile Booking System
  // RegisterFile Only Has 1 Read Port, Using Booking System to avoid any bank conflict before issue.
  // 仅需记录上一条发射指令的 vsrc2 读情况即可。
  private val rBookTable = Reg(Vec(2, Bool()))
  rBookTable(0) := issue.fire & issue.bits.inst.vsrc2v & !issue.bits.inst.realVSrc2(0)
  rBookTable(1) := issue.fire & issue.bits.inst.vsrc2v & !issue.bits.inst.realVSrc2(1)

  // Write RegisterFile Booking System
  // RegisterFile Only has 1 Write Port, Using Booking System to avoid any bank conflict before issue.
  private val wBookTable = Reg(Vec(2, Vec(params.maxLatency, Bool())))
  for(bank <- 0 until 2) {
    wBookTable(bank)(0) := 0.B
    for(lat <- 1 until params.maxLatency) {
      if(lat != params.maxLatency - 1) {
        wBookTable(bank)(lat) := wBookTable(bank)(lat + 1) |
          (issue.fire & issue.bits.inst.vdstv & issue.bits.inst.optionalLatency === lat.U)
      } else {
        wBookTable(bank)(lat) := issue.fire & issue.bits.inst.vdstv & issue.bits.inst.optionalLatency === lat.U
      }
    }
  }

  // FU 吞吐率限制器
  private val intWait = RegInit(0.U(1.W)) // 1Cycle-block before last issue
  // Mov 全吞吐
  // Cmp 全吞吐
  // TODO: Check if 8 is enough or too huge ?
  private val lsuCredit = RegInit(4.U) // 8-Credit for LSU FIFO

  when(issue.fire & issue.bits.inst.fu === VecFuEnum.Int) {
    intWait := 1.U
  }.otherwise {
    intWait := 0.U
  }

  lsuCredit := lsuCredit + lsuCreditGain - (issue.fire & issue.bits.inst.fu === VecFuEnum.Lsu).asUInt

  /*
   * 发射 Stage-0: 从若干输入线程（32）中，选择对齐连续的（4）组线程子块，进入 Stage-2 调度发射。轮转策略，选择存在就绪线程的子块
   *
   * */
  class taggedVInst extends Bundle {
    val task = new VecInstWThreadInfo(params)
    val valid = Bool()
  }

  private val s0Block = Wire(Vec(params.maxThreadCount / 4, DecoupledIO(Vec(4, new taggedVInst()))))
  for(i <- 0 until params.maxThreadCount / 4) {
    s0Block(i).valid := VecInit.tabulate(4)(j => preIssue(i * 4 + j).valid).asUInt.orR
    s0Block(i).bits := VecInit.tabulate(4)(j => {
      val t = Wire(new taggedVInst)
      t.valid := preIssue(i * 4 + j).valid
      t.task := preIssue(i * 4 + j).bits
      preIssue(i * 4 + j).ready := s0Block(i).ready
      t
    })
  }

  private val s0RRArb = Module(new RRArbiter(Vec(4, new taggedVInst()), params.maxThreadCount / 4)).io
  s0RRArb.in <> s0Block
  s0RRArb.out.ready := 1.B

  private val s1Sel = RegNext(s0RRArb.out.bits)

  // Based on s1Sel, do selection.
  private val s1VldSel = VecInit(s1Sel.map(it => {
    val fuRdy = (intWait === 0.U | it.task.inst.fu =/= VecFuEnum.Int) &
                (lsuCredit =/= 0.U | it.task.inst.fu =/= VecFuEnum.Lsu)
    val rfReadRdy = !it.task.inst.vsrc1v | (!rBookTable(it.task.inst.realVSrc1(0)))
    val rfWriteRdy = !it.task.inst.vdstv | (!wBookTable(it.task.inst.realVDst(0))(it.task.inst.optionalLatency))
    val n = Wire(new taggedVInst())
    n.valid := it.valid & fuRdy & rfReadRdy & rfWriteRdy
    n.task := it.task
    n
  }))

  // Do Reduce Selection
  private val s1Final = s1VldSel.reduceTree((l, r) => {
    val n = Wire(new taggedVInst())
    n.valid := l.valid | r.valid
    // 当 r 无效时 / l 有效，且 l 的 PC 比 r 的 PC 更小时， l 更优先
    val lPio = (!r.valid) | (l.valid & (l.task.pc < r.task.pc))
    n.task := Mux(lPio, l.task, r.task)
    n
  })

  private val s2 = RegNext(s1Final)

  // preIssue Response
  preIssueResp.valid := s2.valid
  preIssueResp.htId := s2.task.htId

  // Issue Port
  issue.valid := s1Final.valid
  issue.bits := s1Final.task
  chisel3.assert(issue.ready === 1.B)
}
