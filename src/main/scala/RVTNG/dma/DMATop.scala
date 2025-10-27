package RVTNG.dma

import chisel3._

/*
* 需求：
*    本模块作为 DMA 子系统的顶层，例化 ReorderTransposeBuffer、Ctrl、TLReader、TLWriter、URAMReader、URAMWriter 等单元并完成连线。
*
* 端口：
*    TL-A/D 接口：流式接口，用于发出 Tilelink 读 / 写请求
*    URAM R/W 接口：流式接口，用于发出 URAM 读 / 写请求
*
*/
case class DMAParams(
                      addressBits: Int,
                      dataBits: Int,
                      sourceBits: Int,
                      sinkBits: Int,
                      tcsrBanks: Int = 8)

class DMATop(params: DMAParams) extends Module {
  val tluh = TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = 6)
}
