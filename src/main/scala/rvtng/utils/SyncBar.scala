package rvtng.utils

import chisel3._
import chisel3.util._

// Credit Based Sync Barrier
class SyncBarSource(fifoSize: Int, streamCount: Int, sourceCount: Int) extends Module {
  val inFlit = IO(Input(Bool())) // Sink Release Flit Space
  // Handshake Gating
  val inValid = IO(Input(Vec(sourceCount, Vec(streamCount, Bool()))))
  val inReady = IO(Output(Vec(sourceCount, Bool()))) // Sync Ready

  val outFire = IO(Output(Bool()))
  val outValid = IO(Output(Vec(streamCount, Bool())))
  val outSel   = IO(Output(UInt(log2Ceil(sourceCount).W)))
  val outReady = IO(Input(Vec(streamCount, Bool())))

  // FlitCounting
  val emptyFlit = RegInit(fifoSize.U)

  val inValidR = VecInit(inValid.map(_.asUInt.orR))
  val selValid = Wire(Vec(streamCount, Bool()))
  val selReady = emptyFlit =/= 0.U & outReady.asUInt.andR

  // RR Arbiter of Input
  val arb = Module(new RRArbiter(Vec(streamCount, Bool()), sourceCount))
  arb.io.in <> VecInit.tabulate(sourceCount)(i => {
    val s = Wire(Decoupled(Vec(streamCount, Bool())))
    s.valid := inValidR(i)
    s.bits  := inValid(i)
    inReady(i) := s.ready
    s
  })
  selValid := inValid(arb.io.chosen)
  arb.io.out.ready := selReady
  outSel := arb.io.chosen

  outFire := selReady & arb.io.out.valid
  outValid := VecInit(selValid.map(_ & selReady))

  emptyFlit := emptyFlit + inFlit.asUInt - outFire.asUInt

}

class SyncBarSink[T <: Data](fifoSize: Int, streamCount: Int, sourceCount: Int, gen: T) extends Module {
  val outFlit  = IO(Output(Bool())) // Sink Release Flit Space
  val srcFire  = IO(Input(Bool()))
  val srcValid = IO(Input(Vec(streamCount, Bool())))
  val srcSel   = IO(Input(UInt(log2Ceil(sourceCount).W)))

  // Queue for vld and sel vectors
  val vldQ = Module(new Queue(Vec(streamCount, Bool()), fifoSize, false, false))
  vldQ.io.enq.valid := srcFire
  vldQ.io.enq.bits  := srcValid
  val selQ = Module(new Queue(UInt(log2Ceil(sourceCount).W), fifoSize, false, false))
  selQ.io.enq.valid := srcFire
  selQ.io.enq.bits  := srcSel
  chisel3.assert(!(srcFire & !vldQ.io.enq.ready), "SyncBar Credit Overflow for Valid Vector Queue!")

  // Handshake Gating(No Denied Allow!)
  val inValid = IO(Input(Vec(streamCount, Bool())))
  val in = IO(Input(Vec(streamCount, gen)))

  // Queue for channel Data
  val channelQ = VecInit.tabulate(streamCount)(s => {
    val pldQ = Module(new Queue(gen, fifoSize, false, false))
    pldQ.io.enq.valid := inValid(s)
    pldQ.io.enq.bits  := in(s)
    chisel3.assert(!(inValid(s) & !pldQ.io.enq.ready), "SyncBar Credit Overflow for Payload Queue!")
    pldQ.io.deq
  })

  val curVld = VecInit(channelQ.map(_.valid))
  val curMatch = (curVld.asUInt & vldQ.io.deq.bits.asUInt) === vldQ.io.deq.bits.asUInt

  // Output Connection
  val out = IO(Vec(sourceCount, Decoupled(Vec(streamCount, gen))))
  val outValid = IO(Output(Vec(sourceCount, Vec(streamCount, Bool()))))
  val outGValid = curMatch & vldQ.io.deq.valid
  val outReady = out(selQ.io.deq.bits).ready
  val outFire = outGValid & outReady
  
  vldQ.io.deq.ready := outFire
  selQ.io.deq.ready := outFire

  for(s <- 0 until streamCount) {
    channelQ(s).ready := vldQ.io.deq.bits(s) & outFire
  }
  for(p <- 0 until sourceCount) {
    out(p).valid := outGValid & selQ.io.deq.bits === p.U
    for(s <- 0 until streamCount) {
      out(p).bits(s) := channelQ(s).bits
      outValid(p)(s) := outGValid & selQ.io.deq.bits === p.U & vldQ.io.deq.bits(s)
    }
  }
  outFlit := outFire
}
