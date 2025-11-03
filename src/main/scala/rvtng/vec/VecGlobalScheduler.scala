package rvtng.vec

import chisel3._
import chisel3.util._

class VecInitTaskGroup(params: VecParams) extends Bundle {
  val firstInst = new VecInstWSchedule(params) // 第一条指令
  val dim0 = UInt(16.W) // 维度0 index
  val dim1 = UInt(16.W) // 维度1 index
  val threadCount = UInt(8.W) // 有效线程数目
  val vbase = Vec(4, UInt(48.W)) // 向量基准偏移
  val sbase = Vec(4, UInt(48.W)) // 标量基准偏移
}

/*
* 需求：
*    作为向量模块的全局调度管理器。将输入的 Block、Dim0、Dim1 信息拆分为每个子线程的初始状态，并计算八组基地址（4 Selection * (V + S)）。
* 子线程初始状态广播给所有子线程管理器，第一个空闲的子线程接收相关信号。
*
* 端口：
*    threadTask：流式输出 VecInitTaskGroup 信号，接入任务分派队列（分派到空闲的线程控制顶层）。
*    csrInfo：CSR 流式信息输入，
*    instWindows：非流式输入，探查指令信息，记录首条张量指令用于分派执行。
*    blockStart: 非流式输入脉冲信号，标记张量指令块开始
*
* */
class VecGlobalScheduler extends Module {

}
