package rvtng.vpu

import chisel3._
import chisel3.util._

import Util._


class SFUFPExp[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val inputValid = Input(Bool())

    val result = Output(datatype)
    val outputValid = Output(Bool())

    val busy = Output(Bool())
  })

  val fpExpMod = Module(new FPExp)

  fpExpMod.io.X := io.a.asUInt
  io.result := fpExpMod.io.R.asTypeOf(datatype)

  // TODO
  val stageNum = 4

  val stageValid = Wire(Vec(stageNum, Bool()))
  stageValid(0) := RegNext(io.inputValid)
  for (i <- 1 until stageValid.length) {
    stageValid(i) := RegNext(stageValid(i - 1))
  }

  // io.outputValid := ShiftRegister(io.inputValid, stageNum, true.B)
  io.outputValid := stageValid.last
  io.busy := stageValid.reduce(_||_)
}