package rvtng.uram

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import rvtng.utils._

// Real URAM Implement, 8B Request / Port.
// Read  Latency is 3(Input Switch) + 2(UltraRAM/SRAM) + 1(Output Switch) + 1(Output) == 7 Cycles
// Write Latency is 3(Input Switch) + 1(UltraRAM/SRAM) + 1(Output) == 5 Cycles
class Neo2R1WURAM(r0Port:Int, r1Port: Int, w0Port:Int, portsPerChannel: Int = 4, fifoSize: Int = 16, bank: Int = 32,
                  addrWidth: Int = 17, idWidth: Int = 1, dataWidth: Int = 64) extends Module {
  val log2N = log2Ceil(bank)
  val r0Channel = r0Port / portsPerChannel
  val r1Channel = r1Port / portsPerChannel
  val wChannel = w0Port / portsPerChannel

  val io = IO(new Bundle {
    val r0Req = IO(Flipped(Vec(r0Port, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth)))))
    val r1Req = IO(Flipped(Vec(r1Port, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth)))))

    val r0Resp = IO(Vec(r0Channel,
      Decoupled(Vec(portsPerChannel, new AdvURAMResp(addrWidth, idWidth, dataWidth)))))
    val r1Resp = IO(Vec(r1Channel,
      Decoupled(Vec(portsPerChannel, new AdvURAMResp(addrWidth, idWidth, dataWidth)))))

    val w0Req = IO(Flipped(Vec(w0Port, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth)))))
    val w0Data = IO(Input(Vec(w0Port, UInt(dataWidth.W))))
    val w0Resp = IO(Vec(wChannel,
      Decoupled(Vec(portsPerChannel, new AdvURAMWResp(addrWidth, idWidth, dataWidth)))))
  })

  class WReq extends Bundle {
    val data = UInt(dataWidth.W)
    val ctrl = new AdvURAMReq(addrWidth, idWidth, dataWidth)
  }

  // RW Port Sync Bar Source
  val r0SyncReq = Wire(Vec(r0Port, DecoupledIO(new AdvURAMReq(addrWidth, idWidth, dataWidth))))
  val r1SyncReq = Wire(Vec(r1Port, DecoupledIO(new AdvURAMReq(addrWidth, idWidth, dataWidth))))
  val w0SyncReq  = Wire(Vec(w0Port, Decoupled(new WReq())))

  // R Port XBar
  val r0XBar = Module(new NBBasicXBar(
    r0Port, bank,
    (i: AdvURAMReq) => i.address(log2N - 1, 0),
    new AdvURAMReq(addrWidth, idWidth, dataWidth))).io
  val r1XBar = Module(new NBBasicXBar(
    r1Port, bank,
    (i: AdvURAMReq) => i.address(log2N - 1, 0),
    new AdvURAMReq(addrWidth, idWidth, dataWidth))).io
  val w0XBar = Module(new NBBasicXBar(
    w0Port, bank,
    (i: WReq) => i.ctrl.address(log2N - 1, 0),
    new WReq())).io

  r0XBar.inFlit <> r0SyncReq
  r1XBar.inFlit <> r1SyncReq
  w0XBar.inFlit <> w0SyncReq

  // Control Info Flow
  val r0Ctrl = VecInit(r0XBar.outFlit.map(iter => {
    Queue(Queue(iter, 1, true, false), 1, true, false)
  }))
  val r1Ctrl = VecInit(r1XBar.outFlit.map(iter => {
    Queue(Queue(iter, 1, true, false), 1, true, false)
  }))
  val w0Ctrl = VecInit(w0XBar.outFlit.map(iter => {
    Queue(Queue(iter, 1, true, false), 1, true, false)
  }))

  // Form of Response Signal at URAM Output Stage
  val rawR0Resp = Wire(Vec(bank, DecoupledIO(new AdvURAMResp(addrWidth, idWidth, dataWidth))))
  val rawR1Resp = Wire(Vec(bank, DecoupledIO(new AdvURAMResp(addrWidth, idWidth, dataWidth))))
  val rawW0Resp = Wire(Vec(bank, DecoupledIO(new AdvURAMWResp(addrWidth, idWidth, dataWidth))))

  // Basic Control Info
  rawR0Resp.zip(r0Ctrl).foreach(iter => {
    val (resp, ctrl) = iter
    resp.valid := ctrl.valid
    ctrl.ready := resp.ready
    resp.bits.valid := ctrl.valid
    resp.bits.id := ctrl.bits.flit.id
    resp.ready := 1.B
    chisel3.assert(!(resp.valid & !resp.ready))
  })
  rawR1Resp.zip(r1Ctrl).foreach(iter => {
    val (resp, ctrl) = iter
    resp.valid := ctrl.valid
    ctrl.ready := resp.ready
    resp.bits.valid := ctrl.valid
    resp.bits.id := ctrl.bits.flit.id
    resp.ready := 1.B
    chisel3.assert(!(resp.valid & !resp.ready))
  })
  rawW0Resp.zip(w0Ctrl).foreach(iter => {
    val (resp, ctrl) = iter
    resp.valid := ctrl.valid
    ctrl.ready := resp.ready
    resp.bits.valid := ctrl.valid
    resp.bits.id := ctrl.bits.flit.id
    resp.ready := 1.B
  })

  // RSel Info Flow
  val r0RSel = RegNext(RegNext(r0XBar.outRSel))
  val r1RSel = RegNext(RegNext(r1XBar.outRSel))
  val w0RSel = RegNext(RegNext(w0XBar.outRSel))

  // URAM (2-Cycle Latency) Generation
  for(b <- 0 until bank) {
    val uram = Module(new UltraRAM2R1W(4096, 64, b))
    uram.re0      := r0XBar.outFlit(b).valid
    uram.raddr0   := r0XBar.outFlit(b).bits.flit.address(addrWidth - 1, log2N)
    rawR0Resp(b).bits.data := uram.rdata0
    uram.re1      := r1XBar.outFlit(b).valid
    uram.raddr1   := r1XBar.outFlit(b).bits.flit.address(addrWidth - 1, log2N)
    rawR1Resp(b).bits.data := uram.rdata1
    uram.we       := w0XBar.outFlit(b).valid
    uram.waddr    := w0XBar.outFlit(b).bits.flit.ctrl.address(addrWidth - 1, log2N)
    uram.be       := w0XBar.outFlit(b).bits.flit.ctrl.byteEnable
    uram.wdata    := w0XBar.outFlit(b).bits.flit.data
  }

  // Reverse Mux Generation
  val r0Resp = r0RSel.map(respSel => {
    val resp = Wire(DecoupledIO(new AdvURAMResp(addrWidth, idWidth, dataWidth)))
    resp.bits := RegNext(rawR0Resp(respSel).bits)
    resp.valid := RegNext(rawR0Resp(respSel).valid)
    chisel3.assert(resp.ready)
    resp
  })
  val r1Resp = r1RSel.map(respSel => {
    val resp = Wire(DecoupledIO(new AdvURAMResp(addrWidth, idWidth, dataWidth)))
    resp.bits := RegNext(rawR1Resp(respSel).bits)
    resp.valid := RegNext(rawR1Resp(respSel).valid)
    chisel3.assert(resp.ready)
    resp
  })
  val w0Resp = w0RSel.map(respSel => {
    val resp = Wire(DecoupledIO(new AdvURAMWResp(addrWidth, idWidth, dataWidth)))
    resp.bits := RegNext(rawW0Resp(respSel).bits)
    resp.valid := RegNext(rawW0Resp(respSel).valid)
    chisel3.assert(resp.ready)
    resp
  })

  // Connection of Source-Sink Sync

}

object Adv2R1WURAM extends App {
  ChiselStage.emitSystemVerilog(
    new Neo2R1WURAM(32, 4, 4, 4),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/Neo2R1WURAM")
  )
}

