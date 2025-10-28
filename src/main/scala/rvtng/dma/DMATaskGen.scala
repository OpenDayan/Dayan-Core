package rvtng.dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

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
class DMATaskGen(params: DMAParams) extends Module {

}
