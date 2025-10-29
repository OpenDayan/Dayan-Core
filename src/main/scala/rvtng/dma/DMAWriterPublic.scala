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
* 实现：
*    本模块内置一个特殊的 ReorderCounter 用于 ID 的分配及回收，与 Response 的排序。
*
* */

class DMAWriterPublic extends Module{

}
