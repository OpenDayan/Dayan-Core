package rvtng.dma

import chisel3._
import chisel3.util._

class DMAGroupReqSpilt[D <: Data, T <: Data](params: DMAParams) extends Module {
  val log2MaxOutStanding: Int = params.sourceBits - 1
  val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  val genD: UInt = params.byteType

  val N  = 1 << log2N
  val CN = 1 << log2CN
  val log2BANK = log2N - log2CN
  val BANK = N / CN
  val maxOutStanding = 1 << log2MaxOutStanding

  val groupReq = IO(Flipped(Decoupled(new DMAGroupReq(params))))
  val blockReq = IO(Decoupled(new Bundle {
    val keepInfo  = new DMAWriteTaskInfo(params)
    val groupSz   = UInt(log2N.W) // GroupSize == [64B / (2^transpose)], Spilt Inside
    val groupPad  = UInt(log2N.W)
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5, 6
  }))
  val inQ = Queue(groupReq, 2, false, false)
  val curBlockStart = Reg(UInt((1 + log2MaxOutStanding).W))
  val curMaxSz = ((N - 1).U >> inQ.bits.transpose).asUInt
  blockReq.valid := inQ.valid
  blockReq.bits  := inQ.bits
  inQ.ready := 0.B
  when((inQ.bits.groupSz - curBlockStart) <= curMaxSz) {
    // Pad to nearest
    blockReq.bits.groupSz  := inQ.bits.groupSz - curBlockStart
    inQ.ready := blockReq.ready
  }.otherwise {
    blockReq.bits.groupSz  := curMaxSz
  }
  when((inQ.bits.groupPad - curBlockStart) <= curMaxSz) {
    // Pad to nearest
    blockReq.bits.groupPad := inQ.bits.groupPad - curBlockStart
  }.otherwise {
    blockReq.bits.groupPad := curMaxSz
  }
  when(inQ.fire) {
    curBlockStart := 0.U
  }.elsewhen(blockReq.fire) {
    // only update when fired, lower the bit flip rate.
    curBlockStart := curBlockStart + curMaxSz + 1.U
  }
}
