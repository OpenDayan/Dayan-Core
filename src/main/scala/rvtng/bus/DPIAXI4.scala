package rvtng.bus

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam

class DPIAXI4Base(params: AXI4BundleParameters, Id: Int) extends BlackBox(Map(
                "ADDR_BITS" -> IntParam(params.addressBits),
                "DATA_BITS" -> IntParam(params.dataBits),
                "ID_BITS" -> IntParam(params.idBits),
                "ID" -> IntParam(Id)
              )) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val axi = Flipped(new AXI4Bundle(params))
  })

  addResource("/verilog/DPIAXI4Base.sv")
}

class DPIAXI4(params: AXI4BundleParameters, Id: Int) extends Module {
  val axi = IO(Flipped(new AXI4Bundle(params)))

  private val core = Module(new DPIAXI4Base(params, Id))
  core.io.clock := clock
  core.io.reset := reset
  core.io.axi <> axi
}
