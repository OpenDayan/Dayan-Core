package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._

import sincosutils._

class SFUSinCos[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val inputValid = Input(Bool())

    val resultSin = Output(datatype)
    val resultCos = Output(datatype)
    val outputValid = Output(Bool())

    val busy = Output(Bool())
  })

  val iterNum = 30
  val expWidth = 8
  val sigWidth = 24
  val inIntWidth = 2
  val inFraWidth = 29

  val sinCosMod = Module(new SinCosFN(expWidth, sigWidth, inIntWidth, inFraWidth, iterNum))
  sinCosMod.io.roundingMode := consts.round_minMag

  sinCosMod.io.in := io.a.asUInt
  io.resultSin := sinCosMod.io.out_sin.asTypeOf(datatype)
  io.resultCos := sinCosMod.io.out_cos.asTypeOf(datatype)

  // TODO
  val stageNum = iterNum + 2

  val stageValidRegs = RegInit(VecInit.fill(stageNum)(false.B))
  stageValidRegs(0) := io.inputValid
  for (i <- 1 until stageValidRegs.length) {
    stageValidRegs(i) := stageValidRegs(i - 1)
  }

  // io.outputValid := ShiftRegister(io.inputValid, stageNum, true.B)
  io.outputValid := stageValidRegs.last
  io.busy := stageValidRegs.reduce(_||_)
}