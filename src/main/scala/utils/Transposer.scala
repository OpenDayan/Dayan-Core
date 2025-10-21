package utils

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
  val inValid    = IO(Input(Bool()))
  val inElemSize = IO(Input(UInt(log2Ceil(log2Ceil(N)).W)))
  val inStream   = IO(Input(Vec(S, Vec(N, gen))))
  val outValid   = IO(Output(Bool()))
  val outStream  = IO(Output(Vec(S, Vec(N, gen))))

  // Core Memory Generation
  val mem = VecInit.tabulate(S)(s => VecInit.tabulate(N)(i => {
    val m = Module(new RF1R1WRamBase(log2Ceil(2 * N), gen.getWidth))
    m.io
  }))

  val ctrlIn = Wire(DecoupledIO(inElemSize.cloneType))
  val ctrlOut = Queue(ctrlIn, 2 * N, false, false)
  outValid := RegNext(ctrlOut.valid)

  ctrlIn.bits := inElemSize

  // 2 * N - Buffered Implementation
  // Input Control status
  val inBase  = RegInit(0.U(log2Ceil(2 * N).W))
  val inRound = RegInit(0.U(log2Ceil(2 * N).W))

  val inRor   = inRound << inElemSize
  val inLast  = inRound === ((N.U(log2Ceil(2 * N).W) >> inElemSize).asUInt - 1.U)
  // Ror of input
  val inStreamRotate = VecInit(inStream.map(s => VecInit.tabulate(N)(i => {
    s((i.U - inRor.asUInt)(log2Ceil(N) - 1, 0))
  })))

  ctrlIn.valid := inLast & inValid
  when(inValid) {
    when(inLast) {
      inRound := 0.U
      inBase  := inBase + (N.U(log2Ceil(2 * N).W) >> inElemSize) // 0: N, 1: N / 2
    }.otherwise {
      inRound := inRound + 1.U
    }
  }

  // Output Control status
  val outBase  = RegInit(0.U(log2Ceil(2 * N).W))
  val outRound = RegInit(0.U(log2Ceil(N).W))
  val outLast  = outRound === ((N.U(log2Ceil(2 * N).W) >> ctrlOut.bits).asUInt - 1.U)
  val outRor   = outRound << ctrlOut.bits
  val outRorQ  = RegNext(outRor)
  ctrlOut.ready := outLast
  when(ctrlOut.valid) {
    when(outLast) {
      outRound := 0.U
      outBase  := outBase + (N.U(log2Ceil(2 * N).W) >> ctrlOut.bits)
    }.otherwise {
      outRound := outRound + 1.U
    }
  }

  for(s <- 0 until S) {
    for(p <- 0 until N) {
      mem(s)(p).clk   := clock
      mem(s)(p).reset := reset
      mem(s)(p).we    := inValid
      mem(s)(p).waddr := inBase + inRound
      mem(s)(p).wdata := inStreamRotate(s)(p).asUInt

      mem(s)(p).raddr := outBase +
        (((p.U(log2Ceil(N).W) >> ctrlOut.bits).asUInt - outRound) & ((N.U >> ctrlOut.bits).asUInt - 1.U))
      outStream(s)(p) := mem(s)((p.U + outRorQ.asUInt)(log2Ceil(N) - 1, 0)).rdata
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
