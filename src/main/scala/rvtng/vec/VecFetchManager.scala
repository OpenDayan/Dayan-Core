package rvtng.vec

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.circt.stage.ChiselStage

/*
* 需求:
*    将来自 threads 的多个取指令请求，汇聚为一个独立的请求。
*
* 接口:
*    fetchIn: 流式输入任务
*    : 多组流式输出任务请求，接入到各个 thread。
* */
class VecFetchManager(params: VecParams) extends Module {
  val fetchIn = IO(Vec(params.maxThreadCount, Flipped(Decoupled(UInt(params.log2MaxInstCount.W)))))
  val instPtr = IO(Output(UInt(params.log2MaxInstCount.W)))

  // 这里不用聚合请求
  private val arb = Module(new RRArbiter(fetchIn(0).bits.cloneType, params.maxThreadCount)).io

  arb.in <> fetchIn
  arb.out.ready := 1.B
  instPtr := arb.out.bits
}
