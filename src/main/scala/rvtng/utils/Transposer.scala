/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package dayan.rvtng.utils

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

// This Module Serve as a fine-grained(T-Level) data transposer using Two-Port RegFile.
// Fully Pipelined!
// S: Parallel Group Count, How Many Stream Group Input
// N: Element Count(Elements that needed to be transposed)
// E: Element Size
class Transposer[T <: Data](S: Int, N: Int, gen: T) extends Module{
  require((1 << log2Ceil(N)) == N)

  // Note: No Backward Stalling

  // Log2 Input Element Size
  // 0: 1-T, 1: 2-T, 2: 4-T, 3: 8-T
  val in = IO(Flipped(Decoupled(new Bundle {
    val elemSize = UInt(log2Ceil(log2Ceil(N)).W)
    val stream   = Vec(S, Vec(N, gen))
  })))
  val out = IO(Decoupled(Vec(S, Vec(N, gen))))

  // Core Memory Generation
  val mem = (0 until S).map(s => (0 until N).map(
    n => Module(new RF1R1WRam(log2Ceil(2 * N), gen.getWidth))
  ))
  val memRdata = mem.map(s => VecInit(s.map(_.rdata)))
  val ctrlIn = Wire(DecoupledIO(in.bits.elemSize.cloneType))
  val ctrlOut = Queue(ctrlIn, 2 * N, false, false)

  ctrlIn.bits := in.bits.elemSize

  // 2 * N - Buffered Implementation
  // Input Control status
  val inBase  = RegInit(0.U(log2Ceil(2 * N).W))
  val inRound = RegInit(0.U(log2Ceil(2 * N).W))

  val inRor   = inRound << in.bits.elemSize
  val inLast  = inRound === ((N.U(log2Ceil(2 * N).W) >> in.bits.elemSize).asUInt - 1.U)
  // Ror of input
  val inStreamRotate = VecInit(in.bits.stream.map(s => VecInit.tabulate(N)(i => {
    s((i.U - inRor.asUInt)(log2Ceil(N) - 1, 0))
  })))

  ctrlIn.valid := inLast & in.fire
  when(in.fire) {
    when(inLast) {
      inRound := 0.U
      inBase  := inBase + (N.U(log2Ceil(2 * N).W) >> in.bits.elemSize) // 0: N, 1: N / 2
    }.otherwise {
      inRound := inRound + 1.U
    }
  }

  // Output Control status
  val s1Deny = Wire(Bool())
  val s0Base  = RegInit(0.U(log2Ceil(2 * N).W))
  val s0Round = RegInit(0.U(log2Ceil(N).W))
  val s0Last  = s0Round === ((N.U(log2Ceil(2 * N).W) >> ctrlOut.bits).asUInt - 1.U)
  val s0Ror = s0Round << ctrlOut.bits
  ctrlOut.ready := s0Last & out.ready
  val s1Ror = RegEnable(s0Ror, !s1Deny)
  // TODO: Refractor handshake
  val s1Valid = RegEnable(ctrlOut.valid & s0Base =/= inBase, !s1Deny)
  when(!s1Deny & ctrlOut.valid & s0Base =/= inBase) {
    when(s0Last) {
      s0Round := 0.U
      s0Base  := s0Base + (N.U(log2Ceil(2 * N).W) >> ctrlOut.bits)
    }.otherwise {
      s0Round := s0Round + 1.U
    }
  }
  out.valid := s1Valid
  s1Deny := out.valid & !out.ready
  in.ready := !s1Deny

  for(s <- 0 until S) {
    for(p <- 0 until N) {
      mem(s)(p).we    := in.fire
      mem(s)(p).waddr := inBase + inRound
      mem(s)(p).wdata := inStreamRotate(s)(p).asUInt

      mem(s)(p).raddr := Mux(s1Deny,
        RegNext(mem(s)(p).raddr),
        s0Base + (((p.U(log2Ceil(N).W) >> ctrlOut.bits).asUInt - s0Round) & ((N.U >> ctrlOut.bits).asUInt - 1.U)))
      out.bits(s)(p) := memRdata(s)((p.U + s1Ror.asUInt)(log2Ceil(N) - 1, 0))
    }
  }
}

object Transposer extends App {
  ChiselStage.emitSystemVerilog(
    new Transposer(8, 8, UInt(16.W)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/Transposer"
    )
  )
}
