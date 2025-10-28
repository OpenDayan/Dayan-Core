package rvtng.dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import rvtng.top.TensorInst

/*
* 需求：
*    本模块用于将一条 DMA 指令，拆分为若干 DMA 事务并发送给相应模块执行。
*
* 端口：
*    groupCtrlIf 链接到 DMACSR 模块
*    instReq 链接到 IssueQueue
*    groupReq 链接到 DMAReorderTransposeBuffer
*    readReq 链接到 TLReader / URAMReader
*    transactionAlloc 链接到 DMAReorderTransposeBuffer
*
* 实现：
*    主结构为一个计数器支持的地址 / 子任务生成循环体。循环采用倒序循环实现，反方向循环。
* 每个 Request 发出一次 readReq， 每个 Request Group 发射一次 groupReq。
* */

class DMAWriteTaskInfo(params: DMAParams) extends Bundle{

}

class DMAReadReq(params: DMAParams) extends Bundle {

}

class DMAGroupReq(params: DMAParams) extends Bundle {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  private val genD: UInt = params.byteType
  private val N  = 1 << log2N
  private val CN = 1 << log2CN
  private val log2BANK = log2N - log2CN
  private val BANK = N / CN
  private val maxOutStanding = 1 << log2MaxOutStanding

  val keepInfo  = new DMAWriteTaskInfo(params)
  val groupSz   = UInt(log2MaxOutStanding.W) // GroupSize == [64B / (2^transpose)], Spilt Inside
  val groupPad  = UInt(log2MaxOutStanding.W)
  val reqPad    = UInt(log2N.W)
  val padValue  = Vec(CN, genD)
  val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
}

class DMATaskGen(params: DMAParams) extends Module {
  val log2MaxOutStanding: Int = params.sourceBits - 1
  val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  val genD: UInt = params.byteType

  val N  = 1 << log2N
  val CN = 1 << log2CN
  val log2BANK = log2N - log2CN
  val BANK = N / CN
  val maxOutStanding = 1 << log2MaxOutStanding
  val groupCtrlIf = IO(new Bundle {
    val addr = Output(UInt(log2Ceil(params.tcsrBanks).W))
    val ctrl = Input(new DMACSRStorage)
  })
  val groupReq = IO(Decoupled(new DMAGroupReq(params)))
  val instReq = IO(Flipped(Decoupled(new TensorInst())))

  val readReq = IO(Decoupled(new DMAReadReq(params)))

}
