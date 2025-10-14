package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.DPIRamBase

// Real URAM Implement, N x 8B Request / Ports.
// Latency is Flex-Variable
class Adv2R1WURAM(R0Port:Int, R1Port: Int, W0Port:Int, N: Int, addrWidth: Int, idWidth: Int, dataWidth: Int) {

  val log2N = log2Ceil(N)

  // R Port
  val r0req_i = IO(Flipped(Vec(R0Port, Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))))
  val r0esp_o = IO(Vec(R0Port, Vec(N, new AdvURAMResp(addrWidth, idWidth, dataWidth))))
  val r1req_i = IO(Flipped(Vec(R1Port, Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))))
  val r1esp_o = IO(Vec(R1Port, Vec(N, new AdvURAMResp(addrWidth, idWidth, dataWidth))))

  // R Port Sync Bar
  val r0SyncReq = Wire(Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))
  val r1SyncReq = Wire(Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))

  // R Port XBar Network
  val r0BankedReq = Wire(Vec(N, new AdvURAMReq(addrWidth, idWidth, dataWidth)))
  val r0ValidReq  = Wire(Vec(N, Bool()))
  val r1BankedReq = Wire(Vec(N, new AdvURAMReq(addrWidth, idWidth, dataWidth)))
  val r1ValidReq  = Wire(Vec(N, Bool()))

  // R Port Reverse Routing
  val r0RRoute = Wire(Vec(N, UInt(log2N.W)))
  val r1RRoute = Wire(Vec(N, UInt(log2N.W)))

  // URAM (2-Cycle Latency)

  // Response Switch


  // W Port
  class WReq extends Bundle {
    val data = UInt(dataWidth.W)
    val ctrl = new AdvURAMReq(addrWidth, idWidth, dataWidth)
  }
  val wreq_i = IO(Flipped(Vec(W0Port, Vec(N, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth))))))
  val wdata_i = IO(Input(Vec(W0Port, Vec(N, UInt(dataWidth.W)))))

  // W Port Sync Bar
  val wSyncReq = Wire(Vec(N, Decoupled(new WReq())))

  // W Port XBar Network
  val wBankedReq = Wire(Vec(N, new WReq()))
  val wValidReq  = Wire(Vec(N, Bool()))
}
