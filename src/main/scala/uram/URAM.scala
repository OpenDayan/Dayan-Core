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
  val start_hi = req_i.start(log2Ceil(vecWidth) - 1, log2Ceil(vecWidth) - log2bank)
  val start_lo = req_i.start(log2Ceil(vecWidth) - log2bank - 1 , 0)
  val end_hi = req_i.end(log2Ceil(vecWidth) - 1, log2Ceil(vecWidth) - log2bank)
  val end_lo = req_i.end(log2Ceil(vecWidth) - log2bank - 1 , 0)

  // Check Region Type
  val two_line = Wire(Bool())
  two_line := req_i.start > req_i.end
  // Assertion of two line and flag
  chisel3.assert(!(two_line & req_i.use_n))

  // Sub Request Generation
  val wdataSlices = VecInit.tabulate(bank)(b => VecInit.tabulate(vecWidth/bank)(u => {
    req_i.wdata((b * vecWidth/bank) + u)
  }))
  req_o.zipWithIndex.foreach {case(req, index) => {
    req.address := req_i.address
    req.address_n := req_i.address_n
    req.start := Mux(index.U === start_hi, start_lo, 0.U)
    req.end := Mux(index.U === end_hi, end_lo, (bank-1).U)
    val trueIndex = index.U - start_hi
    req.wdata := wdataSlices(trueIndex(log2bank - 1 , 0))
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
    val prevStart = RegNext(shift_pipe(d - 1))
    shift_pipe(d) := prevStart
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
    new URAMSwitch(addrWidth = 12, vecWidth = 4, latency = 3, bank = 4, UInt(32.W)),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}

trait URAMBackend {
  val latency: Int
  val re: Bool
  val raddr: UInt
  val rdata: UInt
  val we: Bool
  val waddr: UInt
  val wdata: UInt
}

// storageUnit(id, depth, dataWidth) => URAMBackend Modules
class URAM[S <: Module with URAMBackend]
(
  addrWidth: Int, dataWidth: Int, bank: Int, storageUnit: (Int, Int, Int) => S,
  reqFFVec: Seq[Int], respFFVec: Seq[Int]
) extends Module {
  val depth = 1 << addrWidth
  val log2bank = log2Ceil(bank)
  val log4bank = (log2bank + 1) / 2

  // IO
  val rreq = IO(Input(new URAMReq[UInt](addrWidth, bank, UInt(dataWidth.W))))
  val wreq = IO(Input(new URAMReq[UInt](addrWidth, bank, UInt(dataWidth.W))))
  val resp = IO(Output(new URAMResp[UInt](addrWidth, bank, UInt(dataWidth.W))))

  // Build All URAM Backend
  val m = (0 until bank).map(index => Module(storageUnit(index, depth, dataWidth)))

  // Compute Latency For Each Level
  val reqCycle = new Array[Int](log4bank)
  val respCycle = new Array[Int](log4bank)
  for(level <- 1 until log4bank) {
    reqCycle(level) = reqCycle(level - 1) + reqFFVec(level)
  }
  respCycle(log4bank-1) = reqCycle(log4bank-1) + m(0).latency
  for(level <- (0 until log4bank - 1).reverse) {
    respCycle(level) = respCycle(level + 1) + respFFVec(level)
  }
  val latency = reqCycle.zip(respCycle).map { case (req, resp) => resp - req }

  // Compute Reduce Bank For Each Level

  // Build Reduce URAM Switch Network
  val rNet = (0 until log2bank).map(level =>
    (0 until 1<<level).map(
    unit => Module(new URAMSwitch(addrWidth, bank/(1<<level), latency(level), 2, UInt(64.W)))
    )
  )
  val wNet = (0 until log2bank).map(level =>
    (0 until 1<<level).map(
      unit => Module(new URAMSwitch(addrWidth, bank/(1<<level), latency(level), 2, UInt(64.W)))
    )
  )
  for(level <- (0 until log2bank).reverse) {
    for(unit <- 0 until (1 << level)) {
      val rswitch = rNet(level)(unit)
      val wswitch = wNet(level)(unit)
      for(port <- 0 until 1) {
        if(level == log2bank - 1) {
          // First Level of URAMSwitch
          m(2*unit+port).raddr := Mux(rswitch.req_o(port).use_n,
            rswitch.req_o(port).address_n, rswitch.req_o(port).address)
          m(2*unit+port).re := rswitch.req_o(port).valid

          m(2*unit+port).waddr := Mux(wswitch.req_o(port).use_n,
            wswitch.req_o(port).address_n, wswitch.req_o(port).address)
          m(2*unit+port).we := wswitch.req_o(port).valid

          m(2*unit+port).wdata := wswitch.req_o(port).wdata
          rswitch.resp_i(port).data := m(2*unit+port).rdata
        } else {
          rNet(level+1)(2*unit+port).req_i := rswitch.req_o(port)
          wNet(level+1)(2*unit+port).req_i := wswitch.req_o(port)
          rswitch.resp_i(port) := rNet(level+1)(2*unit+port).resp_o
        }
      }
    }
  }
}
