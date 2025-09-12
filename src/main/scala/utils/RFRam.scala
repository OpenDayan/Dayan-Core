package utils

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

// 1-Cycle Fixed Latency
class RF1R1WRamBase(addressWidth: Int, dataWidth: Int) extends
  BlackBox(Map("addressWidth" -> addressWidth, "dataWidth" -> dataWidth)) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Reset())

    val raddr = Input(UInt(addressWidth.W))
    val rdata = Output(UInt(dataWidth.W))

    val we = Input(UInt(1.W))
    val waddr = Input(UInt(addressWidth.W))
    val wdata = Input(UInt(dataWidth.W))
  })
  addResource("/verilog/RF1R1WRamBase.sv")
}
