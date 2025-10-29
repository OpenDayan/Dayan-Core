package rvtng.dma

import chisel3._
import chisel3.util._
import rvtng.bus.TLUH
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
                      addressBits: Int = 48,
                      dataBits: Int = 512,
                      sourceBits: Int = 10,
                      sinkBits: Int = 4,
                      tcsrBanks: Int = 8,
                      transposeLog2CNSize: Int = 3,
                      byteType: UInt = UInt(8.W),
                      uramAddrBits: Int = 20,
                      uramPortPerChannel: Int = 4) {
  val log2MaxOutStanding: Int = sourceBits - 1
  val log2N: Int = log2Ceil(dataBits / byteType.getWidth)
  val genD: UInt = byteType
  val N  = 1 << log2N
  val sizeBits = log2Ceil(log2Ceil(dataBits / 8))
}

class DMATop(params: DMAParams) extends Module {
  val tluh = TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = log2Ceil(params.dataBits / 8))
}
