package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.{RF1R1RWAsyncRamGen, ReorderScoreboard}
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

class DMAWriteReq(params: DMAParams) extends Bundle {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val genD: UInt = params.byteType
  private val N  = 1 << log2N
  val addr = UInt(params.addressBits.W) // Address Should be Aligned to 64B in DRAM Access, Aligned to 8B in URAM Access
  val data = Vec(N, genD)
  val id   = UInt(log2MaxOutStanding.W) // Outstanding Transaction ID
  val isDRAM = Bool()
  // For URAMWriter Swizzle algorithm
  val UramSwizzleS = UInt(8.W)
  val UramSwizzleB = UInt(8.W)
  val UramSwizzleM = UInt(8.W)
}

/*
* 需求：
*    本模块用于将每一个请求组，进一步拆分为写回请求块，发送给 URAM / Tilelink 后段进行执行。
*
* 端口：
*    inStream：  流式握手信号，处理所有来自 ROTB 的 Write 请求。
*    outReqStream：  流式握手信号，输出到 TLWriter / URAMWriter，请求一个 Write 事务。
*    outCompStream： 流式握手信号，来自 TLWriter / URAMWriter，完成一个 Write 事务。
*    instFinish：脉冲信号，标记一条指令完成执行。
* 实现：
*    本模块内置一个特殊的 ReorderScoreboard 用于 ID 的分配及回收，与 Response 的排序。
*
* */

class DMAWriterPublic(params: DMAParams) extends Module{
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  private val genD: UInt = params.byteType
  private val N  = 1 << log2N
  private val CN = 1 << log2CN
  private val log2BANK = log2N - log2CN
  private val BANK = N / CN
  private val maxOutStanding = 1 << log2MaxOutStanding

  // Bypass InStream to OutStream, Prevent any Queue / FIFO Here.
  val outStream = IO(Decoupled(new DMAWriteReq(params)))
  val inStream = IO(Flipped(Decoupled(new DMAStreamDataOut(params))))
  val instFinish = IO(Output(Bool()))

  // Last request FIFO
  private val lrQ = Module(new Queue(Bool(), maxOutStanding, false, false)).io

  // Reorder Scoreboard
  private val scoreboard = Module(new ReorderScoreboard(maxOutStanding, 1))

  outStream.bits.data := inStream.bits.data

  val outCompStream = IO(Flipped(Decoupled(UInt(log2MaxOutStanding.W)))) // Return ID

  // Address Calculation Logic
  private val curBlock   = RegInit(0.U(8.W))
  private val latchAddr  = Reg(UInt(params.addressBits.W))
  private val firstBlock = curBlock === 0.U
  private val lastBlock  = curBlock === inStream.bits.info.block

  // ID Allocation Logic
  private val curIdAllocateHead = RegInit(0.U(log2MaxOutStanding.W))
  private val curIdReleaseHead = RegInit(0.U(log2MaxOutStanding.W))
  private val curIdFree = RegInit(maxOutStanding.U((1 + log2MaxOutStanding).W))
  private val curIdEmpty = curIdFree === 0.U

  outStream.bits.id := curIdAllocateHead
  outStream.valid := inStream.valid & !curIdEmpty
  inStream.ready := outStream.ready & !curIdEmpty

  // Generator Address
  private val curAddr = Mux(firstBlock, inStream.bits.info.addr, latchAddr)
  private val nxtAddr = curAddr + inStream.bits.info.stride
  when(inStream.fire) {
    latchAddr := nxtAddr
    when(lastBlock) {
      curBlock := 0.U
    }.otherwise {
      curBlock := curBlock + 1.U
    }
  }
  outStream.bits.addr := curAddr
  outStream.bits.isDRAM := inStream.bits.info.isDRAM
  outStream.bits.UramSwizzleS := inStream.bits.info.UramSwizzleS
  outStream.bits.UramSwizzleB := inStream.bits.info.UramSwizzleB
  outStream.bits.UramSwizzleM := inStream.bits.info.UramSwizzleM

}
