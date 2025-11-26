package rvcore

import chisel3._
import chisel3.util._

case class CacheParams(
  nSets: Int = 64,
  nWays: Int = 4,
  blockSize: Int = 64, // bytes
  addrBits: Int = 32,
  dataBits: Int = 64,
  idBits: Int = 5, // Added for Register ID tracking
  isICache: Bool = false.B
) {
  val offsetBits = log2Ceil(blockSize)
  val indexBits  = log2Ceil(nSets)
  val tagBits    = addrBits - indexBits - offsetBits
  
  val wordsPerBlock = blockSize / (dataBits/8)
  val wordOffsetBits = log2Ceil(wordsPerBlock)
  // The 'reqOff' uses offsetBits but should only select WORD index, not BYTE index
  // offsetBits includes byte offset.
}

class CacheReq(params: CacheParams) extends Bundle {
  val addr  = UInt(params.addrBits.W)
  val data  = UInt(params.dataBits.W)
  val mask  = UInt((params.dataBits/8).W)
  val cmd   = UInt(4.W) // 0: Read, 1: Write
  val id    = UInt(params.idBits.W) // Destination Register ID
}

class CacheResp(params: CacheParams) extends Bundle {
  val data = UInt(params.dataBits.W)
  val id   = UInt(params.idBits.W)
}

class NonBlockingCache(params: CacheParams, tlParams: TLParams) extends Module {
  val io = IO(new Bundle {
    val cpu = new Bundle {
      val req = Flipped(Decoupled(new CacheReq(params)))
      val resp = Decoupled(new CacheResp(params))
    }
    val tl = new TLBundle(tlParams)
  })

  // Metadata
  val tagArray = Mem(params.nSets, Vec(params.nWays, UInt(params.tagBits.W)))
  val validArray = RegInit(VecInit(Seq.fill(params.nSets)(VecInit(Seq.fill(params.nWays)(false.B)))))
  val dirtyArray = RegInit(VecInit(Seq.fill(params.nSets)(VecInit(Seq.fill(params.nWays)(false.B)))))
  val dataArray = Mem(params.nSets, Vec(params.nWays, Vec(params.wordsPerBlock, UInt(params.dataBits.W))))

  // MSHR (Miss Status Holding Registers)
  val mshrBusy = RegInit(false.B)
  val mshrAddr = Reg(UInt(params.addrBits.W))
  val mshrCmd  = Reg(UInt(4.W))
  val mshrData = Reg(UInt(params.dataBits.W))
  val mshrId   = Reg(UInt(params.idBits.W)) // Store the ID

  // Request parsing
  val reqIndex = io.cpu.req.bits.addr(params.indexBits + params.offsetBits - 1, params.offsetBits)
  val reqTag   = io.cpu.req.bits.addr(params.addrBits - 1, params.indexBits + params.offsetBits)
  
  // Fix W004 warning: extract word offset correctly
  // addr(offsetBits-1, 0) is byte offset within block. 
  // We need word index. (dataBits/8) is bytes per word.
  // So shift right by log2(bytesPerWord)
  val bytesPerWord = params.dataBits/8
  val byteOffset = io.cpu.req.bits.addr(params.offsetBits - 1, 0)
  val reqOff = byteOffset >> log2Ceil(bytesPerWord)

  // Tag Compare
  val hits = Wire(Vec(params.nWays, Bool()))
  var hit = false.B
  var hitWay = 0.U
  
  for (i <- 0 until params.nWays) {
    hits(i) := validArray(reqIndex)(i) && tagArray(reqIndex)(i) === reqTag
    when (hits(i)) {
      hit = true.B
      hitWay = i.U
    }
  }

  // State Machine
  val sIdle :: sAcquire :: sGrant :: sRefill :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // CPU Interface
  io.cpu.req.ready := state === sIdle && !mshrBusy 
  
  val readData = dataArray(reqIndex)(hitWay)(reqOff)
  
  io.cpu.resp.valid := (io.cpu.req.valid && hit && state === sIdle) || (state === sRefill) 
  io.cpu.resp.bits.data := Mux(state === sRefill, mshrData, readData) 
  io.cpu.resp.bits.id   := Mux(state === sRefill, mshrId, io.cpu.req.bits.id)

  when (io.cpu.req.fire) {
    when (!hit) {
      mshrBusy := true.B
      mshrAddr := io.cpu.req.bits.addr
      mshrCmd  := io.cpu.req.bits.cmd
      mshrData := io.cpu.req.bits.data
      mshrId   := io.cpu.req.bits.id
      state := sAcquire
    } .elsewhen (io.cpu.req.bits.cmd === 1.U) {
      dataArray(reqIndex)(hitWay)(reqOff) := io.cpu.req.bits.data
      dirtyArray(reqIndex)(hitWay) := true.B
    }
  }

  // TileLink State Machine (Simplified)
  io.tl.a.valid := state === sAcquire
  io.tl.a.bits := 0.U.asTypeOf(new TLBundleA(tlParams))
  io.tl.a.bits.opcode := TLMessages.Get
  io.tl.a.bits.param := 0.U
  io.tl.a.bits.size := log2Ceil(params.blockSize).U
  io.tl.a.bits.source := 0.U
  io.tl.a.bits.address := mshrAddr & ~((params.blockSize - 1).U(params.addrBits.W))
  io.tl.a.bits.mask := "hFFFFFFFF".U
  
  when (io.tl.a.fire) {
    state := sGrant
  }

  io.tl.d.ready := state === sGrant
  
  when (io.tl.d.fire) {
    // Update arrays...
    val refillIndex = mshrAddr(params.indexBits + params.offsetBits - 1, params.offsetBits)
    val refillTag   = mshrAddr(params.addrBits - 1, params.indexBits + params.offsetBits)
    tagArray(refillIndex)(0.U) := refillTag
    validArray(refillIndex)(0.U) := true.B
    
    state := sRefill
  }
  
  when (state === sRefill) {
    state := sIdle
    mshrBusy := false.B
  }

  io.tl.b.ready := true.B
  io.tl.c.valid := false.B
  io.tl.c.bits := DontCare
  io.tl.e.valid := false.B
  io.tl.e.bits := DontCare
}
