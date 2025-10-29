package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

// Tilelink Adapter

class DMATLReader(params: DMAParams) extends Module{
  val readCmd = IO(Flipped(Decoupled(new DMAReadReq(params))))
  val readResp = IO(Decoupled(new DMAReadComp(params)))
  val tl = IO(TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = params.sizeBits))

  tl.a.valid := readCmd.valid
  readCmd.ready := tl.a.ready
  tl.a.bits.opcode := TLMessages.Get
  tl.a.bits.param := 0.U
  tl.a.bits.size  := log2Ceil(params.dataBits / 8).U
  tl.a.bits.source := Cat(0.U, readCmd.bits.id)
  tl.a.bits.address := readCmd.bits.addr
  tl.a.bits.mask := VecInit.tabulate(params.dataBits / 8)(i => 1.U).asUInt
  tl.a.bits.data := DontCare
  tl.a.bits.corrupt := 0.B

  readResp.bits.data := tl.d.bits.data.asTypeOf(readResp.bits.data)
  readResp.bits.tid := tl.d.bits.source
  readResp.valid := tl.d.valid
  tl.d.ready := readResp.ready
}
