package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._

import rcputils._

class SFURcp[T <: Data](dataWidth: Int, val datatype : T) extends Module {
  val io = IO(new Bundle {
    val a = Input(datatype)
    val inputValid = Input(Bool())

    val result = Output(datatype)
    val outputValid = Output(Bool())

    val busy = Output(Bool())
  })

  val rcpMod = Module(new RcpFN(8, 24))
  rcpMod.io.roundingMode := consts.round_near_even
  rcpMod.io.detectTininess := consts.tininess_afterRounding
  
  rcpMod.io.in_rcp := io.a.asUInt
  io.result := rcpMod.io.out_rcp.asTypeOf(datatype)

  // TODO
  val stageNum = 38

  val stageValidRegs = RegInit(VecInit.fill(stageNum)(false.B))
  stageValidRegs(0) := io.inputValid
  for (i <- 1 until stageValidRegs.length) {
    stageValidRegs(i) := stageValidRegs(i - 1)
  }

  // io.outputValid := ShiftRegister(io.inputValid, stageNum, true.B)
  io.outputValid := stageValidRegs.last
  io.busy := stageValidRegs.reduce(_||_)
}