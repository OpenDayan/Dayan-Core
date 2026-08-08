/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package dayan.rvtng.uram

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import dayan.rvtng.utils._

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
    val r0Req = Flipped(Vec(r0Port, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))
    val r1Req = Flipped(Vec(r1Port, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))

    val r0Resp = Vec(r0Channel,
      Decoupled(Vec(portsPerChannel, new AdvURAMResp(addrWidth, idWidth, dataWidth))))
    val r1Resp = Vec(r1Channel,
      Decoupled(Vec(portsPerChannel, new AdvURAMResp(addrWidth, idWidth, dataWidth))))

    val w0Req = Flipped(Vec(w0Port, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))
    val w0Data = Input(Vec(w0Port, UInt(dataWidth.W)))
    val w0Resp = Vec(wChannel,
      Decoupled(Vec(portsPerChannel, new AdvURAMWResp(addrWidth, idWidth, dataWidth))))
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
    new AdvURAMReq(addrWidth, idWidth, dataWidth), portsPerChannel)).io
  val r1XBar = Module(new NBBasicXBar(
    r1Port, bank,
    (i: AdvURAMReq) => i.address(log2N - 1, 0),
    new AdvURAMReq(addrWidth, idWidth, dataWidth), portsPerChannel)).io
  val w0XBar = Module(new NBBasicXBar(
    w0Port, bank,
    (i: WReq) => i.ctrl.address(log2N - 1, 0),
    new WReq(), portsPerChannel)).io

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
    resp.bits.id := ctrl.bits.flit.ctrl.id
    resp.ready := 1.B
    chisel3.assert(!(resp.valid & !resp.ready))
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
  val r0Resp = r0RSel.zipWithIndex.map(iter => {
    val (respSel, idx) = iter
    val resp = Wire(DecoupledIO(new AdvURAMResp(addrWidth, idWidth, dataWidth)))
    resp.bits := RegNext(rawR0Resp(respSel).bits)
    resp.valid := RegNext(rawR0Resp(respSel).valid && r0Ctrl(respSel).bits.src === idx.U)
    chisel3.assert(!(resp.valid & !resp.ready))
    resp
  })
  val r1Resp = r1RSel.zipWithIndex.map(iter => {
    val (respSel, idx) = iter
    val resp = Wire(DecoupledIO(new AdvURAMResp(addrWidth, idWidth, dataWidth)))
    resp.bits := RegNext(rawR1Resp(respSel).bits)
    resp.valid := RegNext(rawR1Resp(respSel).valid && r1Ctrl(respSel).bits.src === idx.U)
    chisel3.assert(!(resp.valid & !resp.ready))
    resp
  })
  val w0Resp = w0RSel.zipWithIndex.map(iter => {
    val (respSel, idx) = iter
    val resp = Wire(DecoupledIO(new AdvURAMWResp(addrWidth, idWidth, dataWidth)))
    resp.bits := RegNext(rawW0Resp(respSel).bits)
    resp.valid := RegNext(rawW0Resp(respSel).valid && w0Ctrl(respSel).bits.src === idx.U)
    chisel3.assert(!(resp.valid & !resp.ready))
    resp
  })

  // Connection of Source-Sink Sync
  val r0SyncBar = Module(new NeoSyncBar(portsPerChannel, r0Port/portsPerChannel, fifoSize,
    new AdvURAMReq(addrWidth, idWidth, dataWidth), new AdvURAMResp(addrWidth, idWidth, dataWidth))).io
  val r1SyncBar = Module(new NeoSyncBar(portsPerChannel, r1Port/portsPerChannel, fifoSize,
    new AdvURAMReq(addrWidth, idWidth, dataWidth), new AdvURAMResp(addrWidth, idWidth, dataWidth))).io
  val w0SyncBar = Module(new NeoSyncBar(portsPerChannel, w0Port/portsPerChannel, fifoSize,
    new WReq(), new AdvURAMWResp(addrWidth, idWidth, dataWidth))).io

  r0SyncBar.srcStreamI <> io.r0Req
  r0SyncBar.srcStreamO <> r0SyncReq
  r1SyncBar.srcStreamI <> io.r1Req
  r1SyncBar.srcStreamO <> r1SyncReq
  w0SyncBar.srcStreamI <> VecInit(io.w0Req.zip(io.w0Data).map(iter => {
    val (req, wdata) = iter
    val wreq = Wire(DecoupledIO(new WReq()))
    wreq.bits.ctrl := req.bits
    wreq.bits.data := wdata
    wreq.valid := req.valid
    req.ready := wreq.ready
    wreq
  }))
  w0SyncBar.srcStreamO <> w0SyncReq

  r0SyncBar.dstStreamI <> r0Resp
  r1SyncBar.dstStreamI <> r1Resp
  w0SyncBar.dstStreamI <> w0Resp
  for(c <- 0 until (r0Port / portsPerChannel)) {
    io.r0Resp(c).valid := r0SyncBar.dstStreamOVld(c)
    for(p <- 0 until portsPerChannel) {
      io.r0Resp(c).bits(p).valid := r0SyncBar.dstStreamO(c * portsPerChannel + p).valid
      io.r0Resp(c).bits(p).id := r0SyncBar.dstStreamO(c * portsPerChannel + p).bits.id
      io.r0Resp(c).bits(p).data := r0SyncBar.dstStreamO(c * portsPerChannel + p).bits.data
      r0SyncBar.dstStreamO(c * portsPerChannel + p).ready := io.r0Resp(c).ready
    }
  }
  for(c <- 0 until (r1Port / portsPerChannel)) {
    io.r1Resp(c).valid := r1SyncBar.dstStreamOVld(c)
    for(p <- 0 until portsPerChannel) {
      io.r1Resp(c).bits(p).valid := r1SyncBar.dstStreamO(c * portsPerChannel + p).valid
      io.r1Resp(c).bits(p).id := r1SyncBar.dstStreamO(c * portsPerChannel + p).bits.id
      io.r1Resp(c).bits(p).data := r1SyncBar.dstStreamO(c * portsPerChannel + p).bits.data
      r1SyncBar.dstStreamO(c * portsPerChannel + p).ready := io.r1Resp(c).ready
    }
  }
  for(c <- 0 until (w0Port / portsPerChannel)) {
    io.w0Resp(c).valid := w0SyncBar.dstStreamOVld(c)
    for(p <- 0 until portsPerChannel) {
      io.w0Resp(c).bits(p).valid := w0SyncBar.dstStreamO(c * portsPerChannel + p).valid
      io.w0Resp(c).bits(p).id := w0SyncBar.dstStreamO(c * portsPerChannel + p).bits.id
      w0SyncBar.dstStreamO(c * portsPerChannel + p).ready := io.w0Resp(c).ready
    }
  }
}

object Neo2R1WURAM extends App {
  ChiselStage.emitSystemVerilog(
    new Neo2R1WURAM(8, 8, 2, 2, 16, 2),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/Neo2R1WURAM")
  )
}

