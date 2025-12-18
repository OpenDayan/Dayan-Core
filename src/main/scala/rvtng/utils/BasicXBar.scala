package rvtng.utils

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

class BasicXBar[T <: Data](
  inCount: Int, outCount: Int,
  routerPolicy: (T) => UInt, gen: T) extends Module {
  val io = IO(new Bundle {
    val inFlit = Vec(inCount, Flipped(Decoupled(gen)))
    val outFlit = Vec(outCount, Decoupled(new Bundle {
      val flit = Input(gen)
      val src = UInt(log2Ceil(inCount).W)
    }))
  })
  val inFlit = VecInit(io.inFlit.zipWithIndex.map(iter => {
    val (p, idx) = iter
    val payload = Wire(DecoupledIO(new Bundle {
      val flit = Input(gen)
      val src = UInt(log2Ceil(inCount).W)
      val dst = Vec(outCount, Bool()) // 目的端口独热码
    }))
    payload.valid := p.valid
    p.ready := payload.ready
    payload.bits.flit := p.bits
    payload.bits.src := idx.asUInt
    payload.bits.dst := 0.U.asTypeOf(payload.bits.dst)
    payload.bits.dst(routerPolicy(p.bits)) := 1.B
    Queue(payload, 2, false, false)
  }))
  val rdyVec = Wire(Vec(inCount, Vec(outCount, Bool())))
  inFlit.zip(rdyVec).foreach(iter => {
    val (p, rdy) = iter
    p.ready := rdy.asUInt.orR
  })
  val outFlit = VecInit.tabulate(outCount)(idx => {
    // 标记所有目标匹配的源
    val filteredIn = VecInit(inFlit.zipWithIndex.map(iter => {
      val n = Wire(DecoupledIO(new Bundle {
        val flit = Input(gen)
        val src = UInt(log2Ceil(inCount).W)
      }))
      val (p, inIdx) = iter
      n.valid := p.valid & p.bits.dst(idx)
      n.bits.flit := p.bits.flit
      n.bits.src := p.bits.src
      rdyVec(inIdx)(idx) := n.ready & p.bits.dst(idx)
      n
    }))
    // 仲裁器
    val arb = Module(new RRArbiter(filteredIn(0).bits.cloneType, inCount)).io
    arb.in <> filteredIn
    Queue(Queue(arb.out, 1, true, false), 2, false, false)
  })
  io.outFlit <> outFlit
}

object BasicXBar extends App {
  ChiselStage.emitSystemVerilog(
    new BasicXBar(96, 32, (i: UInt) => i(4, 0), UInt(32.W)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/BasicXBar")
  )
}
