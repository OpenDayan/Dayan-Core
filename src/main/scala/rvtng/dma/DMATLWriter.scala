package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

// Tilelink Adapter
class DMATLWriter(params: DMAParams) extends Module{
  val writeReq = IO(Flipped(Decoupled(new DMAWriteReq(params))))
  val writeResp = IO(Decoupled(UInt(params.log2MaxOutStanding.W)))
  // tl Only Map to -> AW;W;B
  val tl = IO(TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = params.sizeBits))

  tl.a.valid := writeReq.valid
  writeReq.ready := tl.a.ready
  tl.a.bits.opcode := TLMessages.PutFullData
  tl.a.bits.param := 0.U
  tl.a.bits.size  := log2Ceil(params.dataBits / 8).U
  tl.a.bits.source := Cat(1.U, writeReq.bits.id)
  tl.a.bits.address := writeReq.bits.addr
  tl.a.bits.mask := VecInit.tabulate(params.dataBits / 8)(i => 1.U).asUInt
  tl.a.bits.data := writeReq.bits.data.asUInt
  tl.a.bits.corrupt := 0.B

  writeResp.bits := tl.d.bits.source(params.log2MaxOutStanding - 1, 0)
  writeResp.valid := tl.d.valid
  tl.d.ready := writeResp.ready
}
