package rvtng.top

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class RVTNGScoreBoard(queueSz: Int = 8, N: Int = 3) extends Module {
  val sbComp  = IO(Vec(N, Flipped(Decoupled(new ScoreboardComp()))))
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))

  private val Q = VecInit(sbComp.map(Queue(_, queueSz)))

  private val arb = Module(new RRArbiter(sbComp(0).bits.cloneType, N)).io
  arb.in <> Q

  instResp.bits.rd   := instReq.bits.instWord(11, 7)
  instResp.bits.data := Mux(arb.out.valid, arb.out.bits.entryId, 0.U)
  instResp.valid := instReq.valid
  instReq.ready  := instResp.ready
  arb.out.ready := instResp.fire
}
