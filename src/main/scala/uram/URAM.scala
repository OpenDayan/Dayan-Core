package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Interface Define of URAM
class URAMReq[T <: Data](addrWidth: Int, vecWidth: Int, gen: T) extends Bundle {
  val valid = Bool()
  val address   = UInt(addrWidth.W)
  val address_n = UInt(addrWidth.W)
  val use_n = Bool()
  val start = UInt(log2Ceil(vecWidth).W)
  val end = UInt(log2Ceil(vecWidth).W)
  val wdata = Vec(vecWidth, gen)
}
class URAMResp[T <: Data](addrWidth: Int, vecWidth: Int, gen: T) extends Bundle {
  val data = Vec(vecWidth, gen)
}

class URAMSwitch[T <: Data]
(addrWidth: Int, vecWidth: Int, latency: Int, bank: Int, gen: T) extends Module {
  require(latency > 0, "URAMSwitch Latency Must be bigger than 0.")
  val log2bank = log2Ceil(bank)
  val req_i = IO(Input(new URAMReq(addrWidth, vecWidth, gen)))
  val req_o = IO(Vec(bank, Output(new URAMReq(addrWidth, vecWidth/bank, gen))))
  val resp_i = IO(Vec(bank, Input(new URAMResp(addrWidth, vecWidth/bank, gen))))
  val resp_o = IO(Output(new URAMResp(addrWidth, vecWidth, gen)))

  // Spill Req
  val start_hi = req_i.start(log2Ceil(vecWidth) - 1, log2bank)
  val start_lo = req_i.start(log2bank - 1 , 0)
  val end_hi = req_i.end(log2Ceil(vecWidth) - 1, log2bank)
  val end_lo = req_i.end(log2bank - 1 , 0)

  // Check Region Type
  val two_line = Wire(Bool())
  two_line := req_i.start > req_i.end
  // Assertion of two line and flag
  chisel3.assert(!(two_line & req_i.use_n))

  // Sub Request Generation
  req_o.zipWithIndex.foreach {case(req, index) => {
    req.address := req_i.address
    req.address_n := req_i.address_n
    req.start := Mux(index.U === start_hi, start_lo, 0.U)
    req.end := Mux(index.U === end_hi, end_lo, (bank-1).U)
    req.wdata := req_i.wdata.slice(index * vecWidth / bank, (index + 1) * vecWidth / bank)
    // Judge Region
    // s <= i
    val at_right_of_start = Wire(Bool())
    val at_left_of_end = Wire(Bool())
    at_right_of_start := index.U >= start_hi
    at_left_of_end := index.U <= end_hi
    when(two_line) {
      req.valid := at_right_of_start | at_left_of_end
      req.use_n := Mux(at_left_of_end, 1.B, 0.B)
    }.otherwise {
      req.valid := at_right_of_start & at_left_of_end
      req.use_n := 0.B
    }
  }}

  // Shifted Register waiting for Resp
  // Opt Note: Shift Pipe should be impled to SRL In FPGA SliceM.
  val shift = Wire(UInt(log2bank.W))
  val shift_pipe = Wire(Vec(latency + 1, UInt(log2bank.W)))
  shift := shift_pipe(latency)
  shift_pipe(0) := start_hi
  for(d <- 1 to latency) {
    val prevShift = RegNext(shift_pipe(d - 1))
    shift_pipe(d) := prevShift
  }

  // Merge Resp
  for (b <- 0 until bank) {
    for(sv <- 0 until (vecWidth / bank)) {
      val v = b * (vecWidth / bank) + sv
      val b_shifted = shift + b.U
      resp_o.data(v) := resp_i(b_shifted(log2bank-1, 0)).data(sv)
    }
  }
}

object URAMSwitch extends App {
  ChiselStage.emitSystemVerilogFile(
    new URAMSwitch(12,16,3,4,UInt(32.W)),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}

trait URAMBackend[T <: Data] {
  val latency: Int
  val raddr: UInt
  val rdata: T
  val we: Bool
  val waddr: UInt
  val wdata: T
}

// storageUnit(id, depth, DataType) => URAMBackend Modules
class URAM[T <: Data, S <: Module with URAMBackend[T]]
(addrWidth: Int, bank: Int, gen: T, storageUnit: (Int, T)) extends Module {

}
