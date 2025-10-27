package rvtng.uram

import rvtng.utils.{GroupXBar, SyncBarSink, SyncBarSource, UltraRAM2R1W, genPipe}
import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

// Real URAM Implement, 32(N) x 8B Request / Ports.
// Read  Latency is 3(Input Switch) + 2(UltraRAM/SRAM) + 1(Output Switch) + 1(Output) == 7 Cycles
// Write Latency is 3(Input Switch) + 1(UltraRAM/SRAM) + 1(Output) == 5 Cycles
class Adv2R1WURAM(R0Port:Int, R1Port: Int, W0Port:Int, GroupCount: Int = 4, fifoSize: Int = 16, N: Int = 32,
                  addrWidth: Int = 17, idWidth: Int = 1, dataWidth: Int = 64) extends Module {

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
  val r0RRouteDelay = genPipe(r0RRoute, 2)(2)
  val r1RRouteDelay = genPipe(r1RRoute, 2)(2)

  // Response Switch
  val r0Resp = RegNext(VecInit.tabulate(N)(i => {
    r0UramDout(r0RRouteDelay(i))
  }))
  val r1Resp = RegNext(VecInit.tabulate(N)(i => {
    r1UramDout(r1RRouteDelay(i))
  }))
  val r0ValidResp = genPipe(r0RValid, 3)(3)
  val r1ValidResp = genPipe(r1RValid, 3)(3)
  val r0IDResp = genPipe(VecInit(r0BankedReq.map(_.id)), 3)(3)
  val r1IDResp = genPipe(VecInit(r1BankedReq.map(_.id)), 3)(3)

  val r0resp_o = IO(Vec(R0Port, Vec(N / GroupCount,
    Decoupled(Vec(GroupCount, new AdvURAMResp(addrWidth, idWidth, dataWidth))))))
  val r1resp_o = IO(Vec(R1Port, Vec(N / GroupCount,
    Decoupled(Vec(GroupCount, new AdvURAMResp(addrWidth, idWidth, dataWidth))))))

  // R Sync Bar Generation
  // GroupCount streams Gathered Sync Bar
  // SyncBar Flit Link
  for(syncBarId <- 0 until (N / GroupCount)) {
    val r0SyncBarSrc = Module(new SyncBarSource(fifoSize, GroupCount, R0Port))
    r0SyncBarSrc.inValid := VecInit.tabulate(R0Port)(inReqId => VecInit.tabulate(GroupCount)(streamId => {
      r0req_i(inReqId)(syncBarId * GroupCount + streamId).ready := r0SyncBarSrc.inReady(inReqId)
      r0req_i(inReqId)(syncBarId * GroupCount + streamId).valid
    }))
    for(streamId <- 0 until GroupCount) {
      r0SyncReq(syncBarId * GroupCount + streamId).valid := r0SyncBarSrc.outValid(streamId)
      r0SyncBarSrc.outReady(streamId) := r0SyncReq(syncBarId * GroupCount + streamId).ready
      r0SyncReq(syncBarId * GroupCount + streamId).bits :=
        r0req_i(r0SyncBarSrc.outSel)(syncBarId * GroupCount + streamId).bits
    }

    val r0SyncBarSink = Module(new SyncBarSink(fifoSize, GroupCount, R0Port, new AdvURAMResp(addrWidth, idWidth, dataWidth)))
    r0SyncBarSink.in := VecInit.tabulate(GroupCount)(streamId => {
      val r = Wire(new AdvURAMResp(addrWidth, idWidth, dataWidth))
      r0SyncBarSink.inValid(streamId) := r0ValidResp(syncBarId * GroupCount + streamId)
      r.valid := r0ValidResp(syncBarId * GroupCount + streamId)
      r.data  := r0Resp(syncBarId * GroupCount + streamId)
      r.id    := r0IDResp(syncBarId * GroupCount + streamId)
      r
    })
    r0SyncBarSrc.inFlit    := r0SyncBarSink.outFlit
    r0SyncBarSink.srcSel   := r0SyncBarSrc.outSel
    r0SyncBarSink.srcFire  := r0SyncBarSrc.outFire
    r0SyncBarSink.srcValid := r0SyncBarSrc.outValid

    for(outRespId <- 0 until R0Port) {
      r0resp_o(outRespId)(syncBarId).valid := r0SyncBarSink.out(outRespId).valid
      r0SyncBarSink.out(outRespId).ready := r0resp_o(outRespId)(syncBarId).ready
      r0resp_o(outRespId)(syncBarId).bits := r0SyncBarSink.out(outRespId).bits
      for(streamId <- 0 until GroupCount) {
        r0resp_o(outRespId)(syncBarId).bits(streamId).valid :=
          r0SyncBarSink.out(outRespId).bits(streamId).valid & r0SyncBarSink.out(outRespId).valid
      }
    }

    val r1SyncBarSrc = Module(new SyncBarSource(fifoSize, GroupCount, R1Port))
    r1SyncBarSrc.inValid := VecInit.tabulate(R1Port)(inReqId => VecInit.tabulate(GroupCount)(streamId => {
      r1req_i(inReqId)(syncBarId * GroupCount + streamId).ready := r1SyncBarSrc.inReady(inReqId)
      r1req_i(inReqId)(syncBarId * GroupCount + streamId).valid
    }))
    for(streamId <- 0 until GroupCount) {
      r1SyncReq(syncBarId * GroupCount + streamId).valid := r1SyncBarSrc.outValid(streamId)
      r1SyncBarSrc.outReady(streamId) := r1SyncReq(syncBarId * GroupCount + streamId).ready
      r1SyncReq(syncBarId * GroupCount + streamId).bits :=
        r1req_i(r1SyncBarSrc.outSel)(syncBarId * GroupCount + streamId).bits
    }

    val r1SyncBarSink = Module(new SyncBarSink(fifoSize, GroupCount, R1Port, new AdvURAMResp(addrWidth, idWidth, dataWidth)))
    r1SyncBarSink.in := VecInit.tabulate(GroupCount)(streamId => {
      val r = Wire(new AdvURAMResp(addrWidth, idWidth, dataWidth))
      r1SyncBarSink.inValid(streamId) := r1ValidResp(syncBarId * GroupCount + streamId)
      r.valid := r1ValidResp(syncBarId * GroupCount + streamId)
      r.data  := r1Resp(syncBarId * GroupCount + streamId)
      r.id    := r1IDResp(syncBarId * GroupCount + streamId)
      r
    })
    r1SyncBarSrc.inFlit    := r1SyncBarSink.outFlit
    r1SyncBarSink.srcSel   := r1SyncBarSrc.outSel
    r1SyncBarSink.srcFire  := r1SyncBarSrc.outFire
    r1SyncBarSink.srcValid := r1SyncBarSrc.outValid

    for(outRespId <- 0 until R1Port) {
      r1resp_o(outRespId)(syncBarId).valid := r1SyncBarSink.out(outRespId).valid
      r1SyncBarSink.out(outRespId).ready := r1resp_o(outRespId)(syncBarId).ready
      r1resp_o(outRespId)(syncBarId).bits := r1SyncBarSink.out(outRespId).bits
      for(streamId <- 0 until GroupCount) {
        r1resp_o(outRespId)(syncBarId).bits(streamId).valid :=
          r1SyncBarSink.out(outRespId).bits(streamId).valid & r1SyncBarSink.out(outRespId).valid
      }
    }
  }

  // R Port XBar
  val r0XBar = Module(new GroupXBar(log2N, Seq(0, log2N, 0), (i: AdvURAMReq) => i.address(log2N - 1, 0),
    new AdvURAMReq(addrWidth, idWidth, dataWidth)))
  val r1XBar = Module(new GroupXBar(log2N, Seq(0, log2N, 0), (i: AdvURAMReq) => i.address(log2N - 1, 0),
    new AdvURAMReq(addrWidth, idWidth, dataWidth)))

  r0XBar.in <> r0SyncReq
  r1XBar.in <> r1SyncReq
  r0XBar.out.ready := 1.B
  r1XBar.out.ready := 1.B
  r0BankedReq := r0XBar.out.bits
  r1BankedReq := r1XBar.out.bits
  r0BankedValidReq := r0XBar.outVld
  r1BankedValidReq := r1XBar.outVld
  r0RRoute := r0XBar.outReverseRoute
  r1RRoute := r1XBar.outReverseRoute
  r0RValid := r0XBar.outRVld
  r1RValid := r1XBar.outRVld

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
  val wRRoute = Wire(Vec(N, UInt(log2N.W)))
  val wRValid = Wire(Vec(N, Bool()))

  // W Response Switch
  val wIDResp = RegNext(VecInit(wBankedReq.map(_.ctrl.id)))
  val wValidResp = RegNext(wRValid)

  val wresp_o = IO(Vec(W0Port, Vec(N / GroupCount,
    Decoupled(Vec(GroupCount, new AdvURAMWResp(addrWidth, idWidth, dataWidth))))))

  // W Port Sync Bar Generation

  for(syncBarId <- 0 until (N / GroupCount)) {
    val wSyncBarSrc = Module(new SyncBarSource(fifoSize, GroupCount, W0Port))
    wSyncBarSrc.inValid := VecInit.tabulate(W0Port)(inReqId => VecInit.tabulate(GroupCount)(streamId => {
      wreq_i(inReqId)(syncBarId * GroupCount + streamId).ready := wSyncBarSrc.inReady(inReqId)
      wreq_i(inReqId)(syncBarId * GroupCount + streamId).valid
    }))
    for(streamId <- 0 until GroupCount) {
      val r = Wire(new WReq())
      r.ctrl := wreq_i(wSyncBarSrc.outSel)(syncBarId * GroupCount + streamId).bits
      r.data := wdata_i(wSyncBarSrc.outSel)(syncBarId * GroupCount + streamId)
      wSyncReq(syncBarId * GroupCount + streamId).valid := wSyncBarSrc.outValid(streamId)
      wSyncBarSrc.outReady(streamId) := wSyncReq(syncBarId * GroupCount + streamId).ready
      wSyncReq(syncBarId * GroupCount + streamId).bits := r
    }

    val wSyncBarSink = Module(new SyncBarSink(fifoSize, GroupCount, W0Port,
      new AdvURAMWResp(addrWidth, idWidth, dataWidth)))
    wSyncBarSink.in := VecInit.tabulate(GroupCount)(streamId => {
      val r = Wire(new AdvURAMWResp(addrWidth, idWidth, dataWidth))
      wSyncBarSink.inValid(streamId) := wValidResp(syncBarId * GroupCount + streamId)
      r.valid := wValidResp(syncBarId * GroupCount + streamId)
      r.id    := wIDResp(syncBarId * GroupCount + streamId)
      r
    })
    wSyncBarSrc.inFlit    := wSyncBarSink.outFlit
    wSyncBarSink.srcSel   := wSyncBarSrc.outSel
    wSyncBarSink.srcFire  := wSyncBarSrc.outFire
    wSyncBarSink.srcValid := wSyncBarSrc.outValid

    for(outRespId <- 0 until W0Port) {
      wresp_o(outRespId)(syncBarId).valid := wSyncBarSink.out(outRespId).valid
      wSyncBarSink.out(outRespId).ready := wresp_o(outRespId)(syncBarId).ready
      wresp_o(outRespId)(syncBarId).bits := wSyncBarSink.out(outRespId).bits
      for(streamId <- 0 until GroupCount) {
        wresp_o(outRespId)(syncBarId).bits(streamId).valid :=
          wSyncBarSink.out(outRespId).bits(streamId).valid & wSyncBarSink.out(outRespId).valid
      }
    }
  }

  // W Port XBar
  val wXBar = Module(new GroupXBar(log2N, Seq(0, log2N, 0), (i: WReq) => i.ctrl.address(log2N - 1, 0), new WReq()))

  wXBar.in <> wSyncReq
  wXBar.out.ready := 1.B
  wBankedReq := wXBar.out.bits
  wBankedValidReq := wXBar.outVld
  wRRoute := wXBar.outReverseRoute
  wRValid := wXBar.outRVld

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

object Adv2R1WURAM extends App {
  ChiselStage.emitSystemVerilog(
    new Adv2R1WURAM(
      R0Port = 2, R1Port = 2, W0Port = 3, GroupCount = 2,
      fifoSize = 16, N = 4, addrWidth = 14, idWidth = 1, dataWidth = 64
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/Adv2R1WURAM")
  )
}

