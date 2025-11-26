package rvcore

import chisel3._
import chisel3.util._

class Fetch(resetVector: BigInt = 0x80000000L) extends Module {
  val io = IO(new Bundle {
    val stall = Input(Bool())
    val jump  = Input(Bool())
    val jumpAddr = Input(UInt(64.W))
    
    val imem = new Bundle {
      val req = Decoupled(new CacheReq(CacheParams(isICache = true.B)))
      val resp = Flipped(Decoupled(new CacheResp(CacheParams(isICache = true.B))))
    }
    
    val out = Decoupled(new IF_ID)
  })

  val pc = RegInit(resetVector.U(64.W))
  
  val nextPc = Wire(UInt(64.W))
  when (io.jump) {
    nextPc := io.jumpAddr
  } .elsewhen (!io.stall && io.imem.req.ready) {
    nextPc := pc + 4.U
  } .otherwise {
    nextPc := pc
  }
  
  when (!io.stall) {
    pc := nextPc
  }

  io.imem.req.valid := !io.stall && !io.jump
  io.imem.req.bits.addr := pc
  io.imem.req.bits.cmd := 0.U
  io.imem.req.bits.data := 0.U
  io.imem.req.bits.mask := 0.U
  io.imem.req.bits.id   := 0.U // Unused for Fetch

  val q = Module(new Queue(new IF_ID, 2))
  
  q.io.enq.valid := io.imem.resp.valid
  q.io.enq.bits.inst := io.imem.resp.bits.data(31, 0)
  q.io.enq.bits.pc := pc 
  
  io.out <> q.io.deq
  
  io.imem.resp.ready := q.io.enq.ready
}
