package rvtng.vec

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.circt.stage.ChiselStage

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
*    blockStart: 脉冲输出给 TaskGen 负责任务生成
* */
class VecFetchManager(params: VecParams) extends Module {
  val fetchIn = IO(Vec(params.maxThreadCount, Flipped(Decoupled(UInt(params.log2MaxInstCount.W)))))
  val instPtr = IO(Output(UInt(params.log2MaxInstCount.W)))
  val instDeq = IO(Output(UInt(params.log2MaxInstCount.W)))
  val instWindows = IO(Input(new VecInstFetchResp(params)))
  val csrInst = IO(Decoupled(new VecInstWSchedule(params)))
  val blockStart = IO(Output(Bool()))
  val blockEnd = IO(Input(Bool()))

  // 这里不用聚合请求
  private val arb = Module(new RRArbiter(fetchIn(0).bits.cloneType, params.maxThreadCount)).io

  arb.in <> fetchIn

  // 内部记录当前状态
  private val curIsInBlock = RegInit(0.B)
  curIsInBlock := (curIsInBlock & !blockEnd) | blockStart

  // 记录首个非块内指令指针
  private val blockEndPtr = Reg(UInt(params.log2MaxInstCount.W))
  when(curIsInBlock & instWindows.valid & instWindows.inst.finishHint) {
    blockEndPtr := instWindows.pc
  }
  arb.out.ready := 1.B

  // 管理 instPtr 和 instDeq 及 csrInst
  private val headIsBlockInst = instWindows.valid & !instWindows.inst.finishHint
  private val headIsCsr       = instWindows.valid & instWindows.inst.finishHint
  when(!curIsInBlock) {
    // 当前处于块外
    instPtr := Mux(headIsCsr, csrInst.ready.asUInt, 0.U)
    instDeq := Mux(headIsCsr, csrInst.ready.asUInt, 0.U)
  }.otherwise {
    // 当前处于块内
    instPtr := arb.out.bits
    instDeq := Mux(blockEnd, instPtr, 0.U)
  }
  csrInst.valid := !curIsInBlock & headIsCsr
  csrInst.bits := instWindows.inst
  blockStart := !curIsInBlock & headIsBlockInst
}
