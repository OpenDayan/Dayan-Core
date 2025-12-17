package rvtng.vpu

import chisel3._
import chisel3.util._

import Util._

import invsqrtutils._

class SFUInvSqrt[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val inputValid = Input(Bool())

    val result = Output(datatype)
    val outputValid = Output(Bool())

    val busy = Output(Bool())
  })

  val invSqrtMod = Module(new InvSqrtFN(8, 24))
  invSqrtMod.io.roundingMode := consts.round_near_even
  invSqrtMod.io.detectTininess := consts.tininess_afterRounding

  invSqrtMod.io.in_invsqrt := io.a.asUInt
  io.result := invSqrtMod.io.out_invsqrt.asTypeOf(datatype)

  // TODO
  val stageNum = 33

  val stageValidRegs = RegInit(VecInit.fill(stageNum)(false.B))
  stageValidRegs(0) := io.inputValid
  for (i <- 1 until stageValidRegs.length) {
    stageValidRegs(i) := stageValidRegs(i - 1)
  }

  // io.outputValid := ShiftRegister(io.inputValid, stageNum, true.B)
  io.outputValid := stageValidRegs.last
  io.busy := stageValidRegs.reduce(_||_)
}