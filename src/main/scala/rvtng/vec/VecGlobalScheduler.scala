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
*    threadTask: 流式输出 VecInitTaskGroup 信号，接入任务分派队列（分派到空闲的线程控制顶层）。
*    csrInfo: CSR 流式信息输入，
*    instWindows: 非流式输入，探查指令信息，记录首条张量指令用于分派执行。
*    blockStart: 非流式输入脉冲信号，标记张量指令块开始
*    blockEnd: 非流式输出脉冲信号，标记张量指令块结束
*    threadFinish: 非流式输入脉冲信号，标记一次 Thread 完成, 内部更新 Scoreboard 信息。
*
* */
class VecGlobalScheduler(params: VecParams) extends Module {
  val threadTask = IO(Decoupled(new VecInitTaskGroup(params)))
  val csrInfo = IO(Input(new VecCSRStorage(params)))
  val instWindows = IO(Input(new VecInstWSchedule(params)))
  val blockStart = IO(Input(Bool()))
  val blockEnd = IO(Output(Bool()))
  val threadFinish = IO(Input(Bool()))

  // 内部状态记录
  val curIsInBlock = RegInit(0.B)
  val curIsValid = RegInit(0.B)

  // Parallel Handling of blockStart and blockEnd pulse.
  curIsInBlock := (curIsInBlock & !blockEnd) | blockStart

  chisel3.assert(!(blockStart & curIsInBlock))
  chisel3.assert(!(blockEnd & !(curIsInBlock)))

  // 第一条指令记录
  val firstInst = RegEnable(instWindows, blockStart)
  threadTask.bits.firstInst := firstInst

  // 内部循环控制机制
  val curInfo = RegEnable(csrInfo, enable = blockStart)
  val curDim0 = Reg(UInt(16.W))
  val curDim1 = Reg(UInt(16.W))
  val curVBase0 = Reg(Vec(4, UInt(48.W)))
  val curVBase1 = Reg(Vec(4, UInt(48.W)))
  val curSBase0 = Reg(Vec(4, UInt(48.W)))
  val curSBase1 = Reg(Vec(4, UInt(48.W)))

  val curDim0Cr =  curInfo.Dim0 === curDim0
  val curDim1Cr = (curInfo.Dim1 === curDim1) & curDim0Cr

  when(blockStart) {
    curDim0 := 0.U
    curDim1 := 0.U
    curVBase0 := 0.U.asTypeOf(curVBase0)
    curVBase1 := 0.U.asTypeOf(curVBase1)
    curSBase0 := 0.U.asTypeOf(curSBase0)
    curSBase1 := 0.U.asTypeOf(curSBase1)
  }

  val nxtVBase0 = VecInit(curVBase0.zipWithIndex.map({
    case (field, idx) =>
      field + curInfo.elements("Dim0VStride" + idx.toString).asUInt
  }))
  val nxtVBase1 = VecInit(curVBase1.zipWithIndex.map({
    case (field, idx) =>
      field + curInfo.elements("Dim1VStride" + idx.toString).asUInt
  }))
  val nxtSBase0 = VecInit(curSBase0.zipWithIndex.map({
    case (field, idx) =>
      field + curInfo.elements("Dim0SStride" + idx.toString).asUInt
  }))
  val nxtSBase1 = VecInit(curSBase1.zipWithIndex.map({
    case (field, idx) =>
      field + curInfo.elements("Dim1SStride" + idx.toString).asUInt
  }))

  when(threadTask.fire) {
    when(curDim0Cr) {
      curDim0 := 0.U
      curVBase0 := nxtVBase1
      curSBase0 := nxtSBase1
      curDim1 := curDim1 + 1.U
      curVBase1 := nxtVBase1
      curSBase1 := nxtSBase1
    }.otherwise {
      curDim0 := curDim0 + 1.U
      curVBase0 := nxtVBase0
      curSBase0 := nxtSBase0
    }
  }

  threadTask.valid := curIsValid
  curIsValid := (curIsValid | blockStart) & !(threadTask.fire & curDim1Cr)

  val onTheFlyCount = RegInit(0.U(params.log2MaxThreadCount.W + 1))
  onTheFlyCount := onTheFlyCount + threadTask.fire.asUInt - threadFinish.asUInt

  blockEnd := (onTheFlyCount === 0.U) & !curIsValid & curIsInBlock

  threadTask.bits.dim0 := curDim0
  threadTask.bits.dim1 := curDim1
  threadTask.bits.threadCount := curInfo.Block
  threadTask.bits.sbase := curSBase0
  threadTask.bits.vbase := curVBase0
}
