package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Interface Define of URAM
class URAMRReq[T <: Data](addrWidth: Int, vecWidth: Int, gen: T) extends Bundle {
  val valid = Bool()
  val address   = UInt(addrWidth.W)
  val address_n = UInt(addrWidth.W)
  val use_n = Bool()
  val start = UInt(log2Ceil(vecWidth).W)
  val end = UInt(log2Ceil(vecWidth).W)
}

class URAMReq[T <: Data](addrWidth: Int, vecWidth: Int, gen: T) extends URAMRReq(addrWidth, vecWidth, gen) {
  val wdata = Vec(vecWidth, gen)
}

class URAMResp[T <: Data](addrWidth: Int, vecWidth: Int, gen: T) extends Bundle {
  val data = Vec(vecWidth, gen)
}

class URAMSwitch[T <: Data]
(addrWidth: Int, val vecWidth: Int, val latency: Int, val bank: Int, val reqFF: Int, val respFF: Int,gen: T) extends Module {
  require(latency > 0, "URAMSwitch Latency Must be bigger than 0.")
  require(reqFF <= 1, "URAMSwitch Only Support Insert One Stage Request FF.")
  require(respFF <= 1, "URAMSwitch Only Support Insert One Stage Response FF.")
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

  val req_out_raw = Wire(Vec(bank, new URAMReq(addrWidth, vecWidth/bank, gen)))
  req_out_raw.zipWithIndex.foreach {case(req, index) => {
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
  if(reqFF != 0) {
    req_o := RegNext(req_out_raw)
  } else {
    req_o := req_out_raw
  }

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
      if(respFF != 0) {
        resp_o.data(v) := RegNext(resp_i(b_shifted(log2bank-1, 0)).data(sv))
      } else {
        resp_o.data(v) := resp_i(b_shifted(log2bank-1, 0)).data(sv)
      }
    }
  }
}

object URAMSwitch extends App {
  ChiselStage.emitSystemVerilogFile(
    new URAMSwitch(addrWidth = 12, vecWidth = 4, latency = 3, bank = 4, reqFF = 1, respFF = 1, UInt(32.W)),
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
  val id: UInt
}

// storageUnit(id, depth, dataWidth) => URAMBackend Modules
class URAM[S <: Module with URAMBackend]
(
  addrWidth: Int, dataWidth: Int, bank: Int, storageUnit: (Int, Int) => S,
  reqFFVec: Seq[Int], respFFVec: Seq[Int]
) extends Module {
  val depth = 1 << addrWidth
  val log2bank = log2Ceil(bank)
  val log4bank = (log2bank + 1) / 2

  // IO
  val rreq = IO(Input(new URAMRReq[UInt](addrWidth, bank, UInt(dataWidth.W))))
  val wreq = IO(Input(new URAMReq[UInt](addrWidth, bank, UInt(dataWidth.W))))
  val resp = IO(Output(new URAMResp[UInt](addrWidth, bank, UInt(dataWidth.W))))

  // Build All URAM Backend
  val m = (0 until bank).map(index => {
    val ultraRam = Module(storageUnit(depth, dataWidth))
    ultraRam.id := index.U
    ultraRam
  })

  // Compute Latency For Each Level
  val reqCycle = new Array[Int](log4bank)
  val respCycle = new Array[Int](log4bank)
  reqCycle(0) = reqFFVec(0)
  for(level <- 1 until log4bank) {
    reqCycle(level) = reqCycle(level) + reqFFVec(level - 1)
  }
  respCycle(log4bank-1) = reqCycle(log4bank-1) + m(0).latency
  for(level <- (0 until log4bank - 1).reverse) {
    respCycle(level) = respCycle(level + 1) + respFFVec(level + 1)
  }
  val latency = reqCycle.zip(respCycle).map { case (req, resp) => resp - req }

  // Compute Reduce Bank For Each Level
  val reduceBank = (0 until log4bank).map(i => {
    var a = 4
    if(i == (log4bank - 1) && bank != (1 << (2*log4bank))) a = 2
    a
  })

  // Build Reduce URAM Switch Network
  val rNet = (0 until log4bank).map(level =>
    (0 until 1<<(level * 2)).map(
      unit => {
        Module(new URAMSwitch(addrWidth, bank/(1<<(level * 2)), latency(level), reduceBank(level),
          reqFFVec(level), respFFVec(level), UInt(dataWidth.W)))
      }
    )
  )
  val wNet = (0 until log4bank).map(level =>
    (0 until 1<<(level * 2)).map(
      unit => {
        Module(new URAMSwitch(addrWidth, bank/(1<<(level * 2)), latency(level), reduceBank(level),
          reqFFVec(level), respFFVec(level), UInt(dataWidth.W)))
      }
    )
  )
  for(level <- (0 until log4bank).reverse) {
    for(unit <- 0 until (1<<(level * 2))) {
      val rswitch = rNet(level)(unit)
      val wswitch = wNet(level)(unit)
      for(port <- 0 until reduceBank(level)) {
        wswitch.resp_i(port).data := VecInit.tabulate(bank/(reduceBank(level)<<(level * 2)))(i => 0.U(dataWidth.W))
        if(level == log4bank - 1) {
          m(reduceBank(level)*unit+port).raddr := Mux(rswitch.req_o(port).use_n,
            rswitch.req_o(port).address_n, rswitch.req_o(port).address)
          m(reduceBank(level)*unit+port).re := rswitch.req_o(port).valid

          m(reduceBank(level)*unit+port).waddr := Mux(wswitch.req_o(port).use_n,
            wswitch.req_o(port).address_n, wswitch.req_o(port).address)
          m(reduceBank(level)*unit+port).we := wswitch.req_o(port).valid

          m(reduceBank(level)*unit+port).wdata := wswitch.req_o(port).wdata(0)
          rswitch.resp_i(port).data(0) := m(reduceBank(level)*unit+port).rdata
        } else {
          rNet(level+1)(reduceBank(level)*unit+port).req_i := rswitch.req_o(port)
          wNet(level+1)(reduceBank(level)*unit+port).req_i := wswitch.req_o(port)
          rswitch.resp_i(port) := rNet(level+1)(reduceBank(level)*unit+port).resp_o
        }
      }
    }
  }
  // Connect Level-0 IO
  val rreq_ext = Wire(new URAMReq(addrWidth, bank, UInt(dataWidth.W)))
  rreq_ext.valid := rreq.valid
  rreq_ext.address := rreq.address
  rreq_ext.address_n := rreq.address_n
  rreq_ext.use_n := rreq.use_n
  rreq_ext.start := rreq.start
  rreq_ext.end := rreq.end
  rreq_ext.wdata := VecInit.tabulate(bank)(i => 0.U(dataWidth.W))
  rNet(0)(0).req_i := rreq_ext
  wNet(0)(0).req_i := wreq
  if(respFFVec(0) != 0) {
    resp := RegNext(rNet(0)(0).resp_o)
  } else {
    resp := rNet(0)(0).resp_o
  }
}

object URAM extends App {
  ChiselStage.emitSystemVerilogFile(
    new URAM(addrWidth = 12, dataWidth = 64, bank = 256,
      (depth, dataWidth) => new UltraRAM(depth, dataWidth),
      Seq(1, 1, 1, 1),
      Seq(1, 1, 1, 1)
    ),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}
