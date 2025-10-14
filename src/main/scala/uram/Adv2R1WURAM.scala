package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.{DPIRamBase, SyncBarSource, UltraRAM2R1W, pipe}

// Real URAM Implement, 32(N) x 8B Request / Ports.
// Read  Latency is 3(Input Switch) + 2(UltraRAM/SRAM) + 1(Output Switch) + 1(Output) == 7 Cycles
// Write Latency is 3(Input Switch) + 1(UltraRAM/SRAM) + 1(Output) == 5 Cycles
class Adv2R1WURAM(R0Port:Int, R1Port: Int, W0Port:Int,
                  fifoSize: Int = 16, N: Int = 32, addrWidth: Int = 17, idWidth: Int = 1, dataWidth: Int = 64) {

  val log2N = log2Ceil(N)

  // R Port
  val r0req_i = IO(Flipped(Vec(R0Port, Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))))
  val r1req_i = IO(Flipped(Vec(R1Port, Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))))

  // R Port Sync Bar Source
  val r0SyncReq = Wire(Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))
  val r1SyncReq = Wire(Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))

  // R Port XBar Network
  val r0BankedReq = Wire(Vec(N, new AdvURAMReq(addrWidth, idWidth, dataWidth)))
  val r0BankedValidReq  = Wire(Vec(N, Bool()))
  val r1BankedReq = Wire(Vec(N, new AdvURAMReq(addrWidth, idWidth, dataWidth)))
  val r1BankedValidReq  = Wire(Vec(N, Bool()))

  // R Port Reverse Routing
  val r0RRoute = Wire(Vec(N, UInt(log2N.W)))
  val r0RValid = Wire(Vec(N, Bool()))
  val r1RRoute = Wire(Vec(N, UInt(log2N.W)))
  val r1RValid = Wire(Vec(N, Bool()))

  // URAM (2-Cycle Latency)

  val r0UramDout = Wire(Vec(N, UInt(dataWidth.W)))
  val r1UramDout = Wire(Vec(N, UInt(dataWidth.W)))
  val r0RRouteDelay = pipe.genPipe(r0RRoute, 2)(2)
  val r1RRouteDelay = pipe.genPipe(r1RRoute, 2)(2)

  // Response Switch
  val r0Resp = RegNext(VecInit.tabulate(N)(i => {
    r0UramDout(r0RRouteDelay(i))
  }))
  val r1Resp = RegNext(VecInit.tabulate(N)(i => {
    r1UramDout(r1RRouteDelay(i))
  }))
  val r0ValidResp = pipe.genPipe(r0RValid, 3)(3)
  val r1ValidResp = pipe.genPipe(r1RValid, 3)(3)

  val r0resp_o = IO(Vec(R0Port, Vec(N, new AdvURAMResp(addrWidth, idWidth, dataWidth))))
  val r1resp_o = IO(Vec(R1Port, Vec(N, new AdvURAMResp(addrWidth, idWidth, dataWidth))))

  // R Sync Bar Generation
  // 4-Stream Gathered Sync Bar
  // SyncBar Flit Link
  val r0SyncFlit = Wire(Vec(N / 4, Bool()))
  val r1SyncFlit = Wire(Vec(N / 4, Bool()))
  val r0SyncFire = Wire(Vec(N / 4, Bool()))
  val r1SyncFire = Wire(Vec(N / 4, Bool()))
  val wSyncFlit = Wire(Vec(N / 4, Bool()))
  for(syncBarId <- 0 until (N / 4)) {
    val r0SyncBarSrc = Module(new SyncBarSource(fifoSize, 4, R0Port))
    r0SyncBarSrc.inFlit := r0SyncFlit(syncBarId)
    r0SyncBarSrc.inValid := VecInit.tabulate(R0Port)(inReqId => VecInit.tabulate(4)(streamId => {
      r0req_i(inReqId)(syncBarId * 4 + streamId).ready := r0SyncBarSrc.inReady(inReqId)
      r0req_i(inReqId)(syncBarId * 4 + streamId).valid
    }))
    r0SyncFire(syncBarId) := r0SyncBarSrc.outFire
    val r1SyncBarSrc = Module(new SyncBarSource(fifoSize, 4, R1Port))
    r1SyncBarSrc.inFlit := r1SyncFlit(syncBarId)
    r1SyncBarSrc.inValid := VecInit.tabulate(R1Port)(inReqId => VecInit.tabulate(4)(streamId => {
      r1req_i(inReqId)(syncBarId * 4 + streamId).ready := r1SyncBarSrc.inReady(inReqId)
      r1req_i(inReqId)(syncBarId * 4 + streamId).valid
    }))
  }

  // W Port
  class WReq extends Bundle {
    val data = UInt(dataWidth.W)
    val ctrl = new AdvURAMReq(addrWidth, idWidth, dataWidth)
  }
  val wreq_i = IO(Flipped(Vec(W0Port, Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))))
  val wdata_i = IO(Input(Vec(W0Port, Vec(N, UInt(dataWidth.W)))))

  // W Port Sync Bar Source
  val wSyncReq = Wire(Vec(N, Decoupled(new WReq())))

  // W Port XBar Network
  val wBankedReq = Wire(Vec(N, new WReq()))
  val wBankedValidReq  = Wire(Vec(N, Bool()))

  // W Port Reverse Routing

  // W Port Sync Bar Sink

  // URAM Generation
  for(b <- 0 until N) {
    val uram = Module(new UltraRAM2R1W(4096, 64, b))
    uram.re0      := r0BankedValidReq(b)
    uram.raddr0   := r0BankedReq(b).address(addrWidth - 1, log2N)
    r0UramDout(b) := uram.rdata0
    uram.re1      := r1BankedValidReq(b)
    uram.raddr1   := r1BankedReq(b).address(addrWidth - 1, log2N)
    r1UramDout(b) := uram.rdata1
    uram.we       := wBankedValidReq(b)
    uram.waddr    := wBankedReq(b).ctrl.address(addrWidth - 1, log2N)
    uram.wdata    := wBankedReq(b).data
  }

}
