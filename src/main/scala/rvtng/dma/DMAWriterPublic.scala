package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

/*
* 需求：
*    本模块用于将每一个请求组，进一步拆分为写回请求块，发送给 URAM / Tilelink 后段进行执行。
*
* 端口：
*
*
* 实现：
*    本模块内置一个特殊的 ReorderScoreboard 用于 ID 的分配及回收，与 Response 的排序。
*
* */

class DMAWriterPublic(params: DMAParams) extends Module{
  val log2MaxOutStanding: Int = params.sourceBits - 1
  val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  val genD: UInt = params.byteType

  val N  = 1 << log2N
  val CN = 1 << log2CN
  val log2BANK = log2N - log2CN
  val BANK = N / CN
  val maxOutStanding = 1 << log2MaxOutStanding

  val inStream = IO(Decoupled(new Bundle {
    val data = Vec(N, genD)
    val info = new DMAWriteTaskInfo(params)
  }))


}
