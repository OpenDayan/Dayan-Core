package utils

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import uram.URAMBackend

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

class UltraRAM(depth: Int, dataWidth: Int) extends Module with URAMBackend{
  val log2Depth = log2Ceil(depth)
  val latency: Int = 2
  val re: Bool = IO(Input(Bool()))
  val raddr: UInt = IO(Input(UInt(log2Depth.W)))
  val rdata: UInt = IO(Output(UInt(dataWidth.W)))
  val we: Bool = IO(Input(Bool()))
  val waddr: UInt = IO(Input(UInt(log2Depth.W)))
  val wdata: UInt = IO(Input(UInt(dataWidth.W)))
  val id: UInt = IO(Input(UInt(32.W)))

  val uBase = Module(new UltraRAMBase(dataWidth, log2Depth))
  uBase.io.core_clk := clock
  uBase.io.reset := reset
  uBase.io.write_enable := we
  uBase.io.read_enable := re
  uBase.io.addra := waddr
  uBase.io.addrb := raddr
  uBase.io.dina := wdata
  uBase.io.id := id
  rdata := uBase.io.doutb
}

object UltraRAM extends App {
  ChiselStage.emitSystemVerilogFile(
    new UltraRAM(4096, 64),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}