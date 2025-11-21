package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.{RF1R1RWAsyncRamGen, ReorderScoreboard}
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

class DMAWriteReq(params: DMAParams) extends Bundle {
  val addr = UInt(params.addressBits.W) // Address Should be Aligned to 64B in DRAM Access, Aligned to 8B in URAM Access
  val data = Vec(params.N, params.genD)
  val id   = UInt(params.log2MaxOutStanding.W) // Outstanding Transaction ID
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

class DMAPublicWriter(params: DMAParams) extends Module{
  private val log2MaxOutStanding: Int = params.log2MaxOutStanding
  private val log2N: Int = params.log2N
  private val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  private val genD: UInt = params.byteType
  private val N  = params.N
  private val CN = 1 << log2CN
  private val log2BANK = log2N - log2CN
  private val BANK = N / CN
  private val maxOutStanding = 1 << log2MaxOutStanding

  // Bypass InStream to OutStream, Prevent any Queue / FIFO Here.
  val inReqStream = IO(Flipped(Decoupled(new DMAStreamDataOut(params))))
  val instFinish = IO(Output(Bool()))

  val outReqStream = IO(Decoupled(new DMAWriteReq(params)))
  val outCompStream = IO(Flipped(Decoupled(UInt(log2MaxOutStanding.W)))) // Return ID

  outReqStream.bits.data := inReqStream.bits.data
  outCompStream.ready := 1.B

  // Address Calculation Logic
  private val curBlock   = RegInit(0.U(8.W))
  private val latchAddr  = Reg(UInt(params.addressBits.W))
  private val firstBlock = curBlock === 0.U
  private val lastBlock  = curBlock === inReqStream.bits.info.block

  // ID Allocation Logic
  private val curIdAllocateHead = RegInit(0.U(log2MaxOutStanding.W))
  private val curIdReleaseHead = RegInit(0.U(log2MaxOutStanding.W))
  private val curIdFree = RegInit(maxOutStanding.U((1 + log2MaxOutStanding).W))
  private val curIdEmpty = curIdFree === 0.U

  outReqStream.bits.id := curIdAllocateHead
  outReqStream.valid := inReqStream.valid & !curIdEmpty
  inReqStream.ready := outReqStream.ready & !curIdEmpty
  when(outReqStream.fire) {
    curIdAllocateHead := curIdAllocateHead + 1.U
  }

  // Generator Address
  private val curAddr = Mux(firstBlock, inReqStream.bits.info.addr, latchAddr)
  private val nxtAddr = curAddr + inReqStream.bits.info.stride
  when(inReqStream.fire) {
    latchAddr := nxtAddr
    when(lastBlock) {
      curBlock := 0.U
    }.otherwise {
      curBlock := curBlock + 1.U
    }
  }
  outReqStream.bits.addr := curAddr
  outReqStream.bits.isDRAM := inReqStream.bits.info.isDRAM
  outReqStream.bits.UramSwizzleS := inReqStream.bits.info.UramSwizzleS
  outReqStream.bits.UramSwizzleB := inReqStream.bits.info.UramSwizzleB
  outReqStream.bits.UramSwizzleM := inReqStream.bits.info.UramSwizzleM

  // Completion Logic
  // Last request FIFO
  // TODO: Use flow control, and limit size. (32 is fairly enough?)
  private val lrQ = Module(new Queue(Bool(), maxOutStanding, false, false)).io
  lrQ.enq.bits := lastBlock & inReqStream.bits.info.isLastInst
  lrQ.enq.valid := outReqStream.fire

  // Reorder Scoreboard
  // TODO: Use flow control, and limit size. (32 is fairly enough?)
  private val scoreboard = Module(new ReorderScoreboard(maxOutStanding, 1))
  scoreboard.comp.valid := outCompStream.valid
  scoreboard.comp.reqGroupID := outCompStream.bits
  scoreboard.comp.reqGroupSize := DontCare
  scoreboard.check.reqGroupID := curIdReleaseHead
  lrQ.deq.ready := scoreboard.check.reqGroupValid
  instFinish := lrQ.deq.bits & scoreboard.check.reqGroupValid
  scoreboard.check.fire := scoreboard.check.reqGroupValid
  when(scoreboard.check.reqGroupValid) {
    curIdReleaseHead := curIdReleaseHead + 1.U
  }
  curIdFree := curIdFree + scoreboard.check.reqGroupValid.asUInt - outReqStream.fire

}
