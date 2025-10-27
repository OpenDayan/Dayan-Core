package rvtng.utils

import chisel3._
import chisel3.util._

// Fake DPIRam with fixed 32b address and 64b data
class DPIRamBase extends BlackBox
  with HasBlackBoxResource {

    val io = IO(new Bundle {
      val id = Input(UInt(32.W))
      val clk = Input(Clock())
      val reset = Input(Reset())

      val re = Input(Bool())
      val raddr = Input(UInt(32.W))
      val rdata = Output(UInt(64.W))

      val we = Input(Bool())
      val waddr = Input(UInt(32.W))
      val wdata = Input(UInt(64.W))
    })
    addResource("/verilog/DPIRamBase.sv")
}
