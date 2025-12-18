package rvtng.utils

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util.{LockingArbiterLike, _}

class XBarUnionArbiter[T <: Data](gen: T, n: Int)
extends LockingArbiterLike[T](gen, n, 1, None) {
  // this register is not initialized on purpose, see #267
//  lazy val lastGrant = RegEnable(io.chosen, io.out.fire)
//  lazy val grantMask = (0 until n).map(_.asUInt > lastGrant)
  lazy val grantMask = IO(Input(Vec(n, Bool())))
  lazy val validMask = io.in.zip(grantMask).map { case (in, g) => in.valid && g }

  override def grant: Seq[Bool] = {
    val ctrl = ArbiterCtrl((0 until n).map(i => validMask(i)) ++ io.in.map(_.valid))
    (0 until n).map(i => ctrl(i) && grantMask(i) || ctrl(i + n))
  }

  override lazy val choice = WireDefault((n - 1).asUInt)
  for (i <- n - 2 to 0 by -1)
    when(io.in(i).valid) { choice := i.asUInt }
  for (i <- n - 1 to 1 by -1)
    when(validMask(i)) { choice := i.asUInt }
}

// Non-Blocking XBar
//   Note: outFlit Should Always be ready
class NBBasicXBar[T <: Data](
  inCount: Int, outCount: Int,
  routerPolicy: (T) => UInt, gen: T) extends Module {
  val io = IO(new Bundle {
    val inFlit = Vec(inCount, Flipped(Decoupled(gen)))
    val outFlit = Vec(outCount, Decoupled(new Bundle {
      val flit = Input(gen)
      val src = UInt(log2Ceil(inCount).W)
    }))
    val outRSel = Output(Vec(inCount, UInt(log2Ceil(outCount).W)))
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
  val outRSel = RegNext(RegNext(
    VecInit(inFlit.map(iter => routerPolicy(iter.bits.flit)))
  ))
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
    chisel3.assert(!(!io.outFlit(idx).ready && io.outFlit(idx).valid))
    // 仲裁器
    // TODO: 换用 XBar 联合仲裁器 XBarUnionArbiter，同一个 Channel 的 Request 尽可能同时响应
    val arb = Module(new RRArbiter(filteredIn(0).bits.cloneType, inCount)).io
    arb.in <> filteredIn
    Queue(Queue(arb.out, 1, true, false), 1, true, false) // HARD PIPE
  })
  io.outFlit <> outFlit
  io.outRSel := outRSel
}

object NBBasicXBar extends App {
  ChiselStage.emitSystemVerilog(
    new NBBasicXBar(96, 32, (i: UInt) => i(4, 0), UInt(32.W)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/BasicXBar")
  )
}
