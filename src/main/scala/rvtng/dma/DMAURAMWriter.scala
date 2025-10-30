package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._
import rvtng.uram.{AdvURAMReq, AdvURAMWResp, SwizzleAddr}

// URAM Adapter

class DMAURAMWriter(params: DMAParams) extends Module {
  val writeReq = IO(Flipped(Decoupled(new DMAWriteReq(params))))
  val writeResp = IO(Decoupled(UInt(params.log2MaxOutStanding.W)))

  private val uramPort = params.dataBits / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  val uramReq  = IO(Vec(uramPort, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramWData = IO(Output(Vec(uramPort, UInt(64.W))))

  val uramResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMWResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

  val issuedVec = Wire(Vec(uramChannel, Bool()))
  val issuedAll = issuedVec.asUInt.andR
  writeReq.ready := issuedAll
  uramWData := writeReq.bits.data.asTypeOf(uramWData)
  for(i <- 0 until uramPort) {
    val issuedMask = RegInit(0.B)
    uramReq(i).valid := writeReq.valid & !issuedMask
    if((i % uramPortPerChannel) == 0) {
      // TODO: I Think the handshake logic here can be further simplified.
      issuedVec(i / uramPortPerChannel) := issuedMask | uramReq((i / uramPortPerChannel) * uramPortPerChannel).fire
    }
    issuedMask := (issuedMask | uramReq((i / uramPortPerChannel) * uramPortPerChannel).fire) & !issuedAll
    val addr = writeReq.bits.addr + (i * 8).U
    uramReq(i).bits.address := SwizzleAddr(addr,
      writeReq.bits.UramSwizzleS, writeReq.bits.UramSwizzleB, writeReq.bits.UramSwizzleM) >> 3
    uramReq(i).bits.id := writeReq.bits.id
  }

  val respVec = Wire(Vec(uramChannel, Bool()))
  val respAll = respVec.asUInt.andR
  writeResp.valid := respAll
  for(i <- 0 until uramChannel) {
    respVec(i) := uramResp(i).valid
    uramResp(i).ready := respAll
  }
  writeResp.bits := uramResp(0).bits(0).id
}
