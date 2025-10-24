package dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

class DMAGroupReqSpilt[D <: Data, T <: Data](log2MaxOutStanding: Int, log2N: Int, log2CN: Int,
                                             genD: D, genT: T) extends Module {
  val CN = 1 << log2CN
  val N = 1 << log2N
  val groupReq = IO(Flipped(Decoupled(new Bundle {
    val keepInfo  = UInt(genT.getWidth.W)
    val groupSz   = UInt(log2MaxOutStanding.W) // GroupSize == [64B / (2^transpose)], Spilt Inside
    val groupPad  = UInt(log2MaxOutStanding.W)
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
  })))
  val blockReq = IO(Decoupled(new Bundle {
    val keepInfo  = UInt(genT.getWidth.W)
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
    blockReq.bits.groupPad := inQ.bits.groupPad - curBlockStart - 1.U
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
