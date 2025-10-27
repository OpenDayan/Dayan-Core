package RVTNG.dma

import RVTNG.utils.RF1R1RWAsyncRamGen
import chisel3._
import chisel3.util._

class DMACSRStorage extends Bundle {
  val Block = UInt(24.W)
  val Dim0  = UInt(24.W)
  val Dim1  = UInt(24.W)
  val Dim2  = UInt(24.W)

  val InBlockStride  = UInt(28.W)
  val InDim0Stride   = UInt(28.W)
  val InDim1Stride   = UInt(28.W)
  val InDim2Stride   = UInt(28.W)
  val OutBlockStride = UInt(28.W)
  val OutDim0Stride  = UInt(28.W)
  val OutDim1Stride  = UInt(28.W)
  val OutDim2Stride  = UInt(28.W)

  val BlockTranspose    = UInt(8.W)
  val BlockPadding      = UInt(8.W)
  val RequestPadding    = UInt(8.W)
  val PaddingValue      = UInt(8.W)
  val UramWriteSwizzleS = UInt(8.W)
  val UramWriteSwizzleB = UInt(8.W)
  val UramWriteSwizzleM = UInt(8.W)
  val UramReadSwizzleS  = UInt(8.W)
  val UramReadSwizzleB  = UInt(8.W)
  val UramReadSwizzleM  = UInt(8.W)
}

class DMACSR extends DMACSRStorage {
  val CheckSum = UInt(20.W)
}


/*
* 需求：
*    DMA 模块的 TCSR。向 IssueQueue / TaskSpilt 提供控制信号，同时接收指令直接控制。
*
* 端口：
*    张量指令请求接口：流式接口，用于接收 TCSR 读 / 写请求
*    张量指令返回接口：流式接口，用于返回 TCSR 请求结果（如果有）
*    组配置端口：1R，读取一组张量配置信息，供指令发射逻辑逻辑使用。
* */
class DMAControlStatusRegisters(params: DMAParams) extends Module {
  val instReq = IO(Flipped(Decoupled(Bool())))

  val groupCtrlIf = IO(new Bundle {
    val addr = Input(UInt(log2Ceil(params.tcsrBanks).W))
    val ctrl = Output(new DMACSR)
  })

  val cksumArray = VecInit.tabulate(params.tcsrBanks)(i => {
    Wire(UInt(20.W))
  })
  val TCSRPrototype = new DMACSRStorage
  TCSRPrototype.elements.foreach {
    case (name, fieldGen) =>
      val mem = Module(new RF1R1RWAsyncRamGen(log2Ceil(params.tcsrBanks), 0, fieldGen))
      mem.raddr := groupCtrlIf.addr
      groupCtrlIf.ctrl.elements(name) := mem.rdata
  }
}
