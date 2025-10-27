package RVTNG.utils

import chisel3._
import chisel3.util._

class UnsignedMulBase(AWIDTH: Int = 27, BWIDTH: Int = 18) extends
  BlackBox(Map("AWIDTH" -> AWIDTH, "BWIDTH" -> BWIDTH))
  with HasBlackBoxResource {

  val io = IO(new Bundle {
    val a = Input(UInt(AWIDTH.W))
    val b = Input(UInt(BWIDTH.W))
    val c = Output(UInt((AWIDTH+BWIDTH).W))
  })
  addResource("/verilog/UnsignedMulBase.sv")
}
