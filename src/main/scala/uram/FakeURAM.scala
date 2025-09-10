package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.DPIRamBase

// Fake URAM Implement, N x 8B Request Ports.
// Latency is Flex-Variable
// Using DPI Connecting to Verilator Main Machine
class FakeURAM(ports: Int, addrWidth: Int, idWidth: Int, dataWidth: Int) extends Module {
  val rreq_i = IO(Flipped(Vec(ports, Decoupled(new AdvURAMRReq(addrWidth, idWidth, dataWidth)))))
  val resp_o = IO(Vec(ports, new AdvURAMResp(addrWidth, idWidth, dataWidth)))
  val wreq_i = IO(Flipped(Vec(ports, Decoupled(new AdvURAMWReq(addrWidth, idWidth, dataWidth)))))

  val resp = Wire(Vec(ports, new AdvURAMResp(addrWidth, idWidth, dataWidth)))
  val dpi_cores = (0 until ports).map(i => {
    val core = Module(new DPIRamBase())
    core.io.clk := clock
    core.io.reset := reset
    core.io.id := i.U
    core.io.re := rreq_i(i).valid
    rreq_i(i).ready := 1.B
    core.io.raddr := rreq_i(i).bits.address
    core.io.we := wreq_i(i).valid
    wreq_i(i).ready := 1.B
    core.io.waddr := wreq_i(i).bits.address
    core.io.wdata := wreq_i(i).bits.data

    // rdata is one beat after re.
    val dReq = RegNext(rreq_i(i).bits)
    val respV = RegNext(rreq_i(i).fire)
    resp(i).data := core.io.rdata
    resp(i).valid := respV
    resp(i).id := dReq.id
    core
  })

  resp_o := resp

}

object FakeURAM extends App {
  ChiselStage.emitSystemVerilog(
    new FakeURAM(4, 32, 0, 64),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/FakeURAM"
    )
  )
}

