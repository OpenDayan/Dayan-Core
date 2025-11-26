package rvcore

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import rvtng.top.{TensorInst, TensorResp}

class RISCVCoreTestTop extends Module {
  val io = IO(new Bundle {
    // External Memory Interface (TileLink)
    val icacheTL = new TLBundle(TLParams())
    val dcacheTL = new TLBundle(TLParams())
    
    // NPU Interface
    val npuReq  = Decoupled(new TensorInst)
    val npuResp = Flipped(Decoupled(new TensorResp))
    
    // Debug / Status
    val error = Output(Bool())
  })

  val core = Module(new RISCVCore(0x80000000L))
  
  io.icacheTL <> core.io.icacheTL
  io.dcacheTL <> core.io.dcacheTL
  io.npuReq   <> core.io.npuReq
  io.npuResp  <> core.io.npuResp
  io.error    := core.io.error
}

object RISCVCoreTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new RISCVCoreTestTop(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/RISCVCoreTestTop"
    )
  )
}

