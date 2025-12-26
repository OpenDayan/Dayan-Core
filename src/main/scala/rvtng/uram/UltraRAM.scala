package rvtng.uram

import chisel3._
import chisel3.util._

class UltraRAMBase(DWIDTH: Int = 72, AWIDTH: Int = 12) extends
  BlackBox(Map("DWIDTH" -> DWIDTH, "AWIDTH" -> AWIDTH))
  with HasBlackBoxResource {

  val io = IO(new Bundle {
    val core_clk = Input(Clock())
    val reset = Input(Reset())
    val write_enable = Input(Bool ())
    val read_enable  = Input(Bool ())
    val addra = Input(UInt(AWIDTH.W))
    val dina = Input(UInt(DWIDTH.W))
    val addrb = Input(UInt(AWIDTH.W))
    val doutb = Output(UInt(DWIDTH.W))
    val id = Input(UInt(32.W))
  })
  addResource("/verilog/UltraRAMBase.sv")
  require(DWIDTH <= 72, "In URAM, data width should be equal or less than 72bits")
  require(AWIDTH <= 12, "In URAM, address width should be equal or less than 12bits")

}

class DPIUltraRAM2R1WBase(DWIDTH: Int = 64, AWIDTH: Int = 12) extends
  BlackBox(Map("DWIDTH" -> DWIDTH, "AWIDTH" -> AWIDTH))
  with HasBlackBoxResource {

  val io = IO(new Bundle {
    val core_clk = Input(Clock())
    val reset = Input(Reset())
    val write_enable = Input(Bool ())
    val byte_enable = Input(UInt((DWIDTH/8).W))
    val addra = Input(UInt(AWIDTH.W))
    val dina = Input(UInt(DWIDTH.W))

    val read_enable0  = Input(Bool ())
    val addrb0 = Input(UInt(AWIDTH.W))
    val doutb0 = Output(UInt(DWIDTH.W))

    val read_enable1  = Input(Bool ())
    val addrb1 = Input(UInt(AWIDTH.W))
    val doutb1 = Output(UInt(DWIDTH.W))

    val id = Input(UInt(32.W))
  })
  addResource("/verilog/UltraRAM2R1WBase.sv")
  addResource("/verilog/UltraRAMBase.sv")
  require(DWIDTH <= 72, "In URAM, data width should be equal or less than 72bits")
  require(AWIDTH <= 12, "In URAM, address width should be equal or less than 12bits")

}

class UltraRAM2R1W(depth: Int, dataWidth: Int, ID: Int) extends Module {
  val log2Depth = log2Ceil(depth)
  val latency: Int = 2

  val re0: Bool = IO(Input(Bool()))
  val raddr0: UInt = IO(Input(UInt(log2Depth.W)))
  val rdata0: UInt = IO(Output(UInt(dataWidth.W)))
  val re1: Bool = IO(Input(Bool()))
  val raddr1: UInt = IO(Input(UInt(log2Depth.W)))
  val rdata1: UInt = IO(Output(UInt(dataWidth.W)))

  val we: Bool = IO(Input(Bool()))
  val be: UInt = IO(Input(UInt((dataWidth/8).W)))
  val waddr: UInt = IO(Input(UInt(log2Depth.W)))
  val wdata: UInt = IO(Input(UInt(dataWidth.W)))

  val uBase = Module(new DPIUltraRAM2R1WBase(dataWidth, log2Depth))
  uBase.io.core_clk := clock
  uBase.io.reset := reset
  uBase.io.write_enable := we
  uBase.io.byte_enable := be
  uBase.io.addra := waddr
  uBase.io.dina := wdata

  uBase.io.read_enable0 := re0
  uBase.io.addrb0 := raddr0
  rdata0 := uBase.io.doutb0

  uBase.io.read_enable1 := re1
  uBase.io.addrb1 := raddr1
  rdata1 := uBase.io.doutb1

  uBase.io.id := ID.U(32.W)
}
