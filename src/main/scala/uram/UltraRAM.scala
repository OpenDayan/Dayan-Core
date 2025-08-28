package uram

import chisel3._
import chisel3.util.HasBlackBoxResource
import circt.stage.ChiselStage

class UltraRAMBase(DWIDTH: Int = 72, AWIDTH: Int = 12) extends
  BlackBox(Map("DWIDTH" -> DWIDTH, "AWIDTH" -> AWIDTH))
  with HasBlackBoxResource {

  val io = IO(new Bundle {
    val core_clk = Input(Clock())
    val reset = Input(Reset())
    val write_enable = Input(Bool ())
    val read_enable  = Input(Bool ())
    val addra = Input(UInt(AWIDTH.W))
    val dina = Input(SInt(DWIDTH.W))
    val addrb = Input(UInt(AWIDTH.W))
    val doutb = Output(UInt(DWIDTH.W))
  })
  addResource("/verilog/UltraRAMBase.sv")
  require(DWIDTH <= 72, "In URAM, data width should be equal or less than 72bits")
  require(AWIDTH <= 12, "In URAM, address width should be equal or less than 12bits")

}

class UltraRAM extends Module with URAMBackend[UInt]{
  val latency: Int = 2
  val re: Bool = IO(Input(Bool()))
  val raddr: UInt = IO(Input(UInt(12.W)))
  val rdata: UInt = IO(Output(UInt(64.W)))
  val we: Bool = IO(Input(Bool()))
  val waddr: UInt = IO(Input(UInt(12.W)))
  val wdata: UInt = IO(Input(UInt(64.W)))

  val uBase = Module(new UltraRAMBase(72, 12))
  uBase.io.core_clk := clock
  uBase.io.reset := reset
  uBase.io.write_enable := we
  uBase.io.read_enable := re
  uBase.io.addra := waddr
  uBase.io.addrb := raddr
  uBase.io.dina := wdata.zext
  rdata := uBase.io.doutb(63, 0)
}

object UltraRAM extends App {
  ChiselStage.emitSystemVerilogFile(
    new UltraRAM(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}