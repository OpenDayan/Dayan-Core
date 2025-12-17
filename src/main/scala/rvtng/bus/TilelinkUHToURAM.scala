package rvtng.bus

import chisel3._
import chisel3.util._
import rvtng.bus.TLMessages._
import rvtng.uram._

class TilelinkUHToURAM(val tlParms: TLBundleParameters, val uramAddrBits: Int, val uramPortPerChannel: Int) extends Module {
  // tl can be mapped to URAM - R+RResp / W+WResp, with no Burst Transaction, no ID reuse.
  val tl = IO(Flipped(new TLBundle(tlParms)))

  private val uramPorts = tlParms.dataBits / 64
  private val uramChannel = uramPorts / uramPortPerChannel

  // TODO: Support More than one channel in future
  assert(uramChannel == 1)

  val uramRReq  = IO(Vec(uramPorts, Decoupled(
    new AdvURAMReq(uramAddrBits, tlParms.sourceBits, 64))))
  val uramRResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMResp(uramAddrBits, tlParms.sourceBits, 64))))))

  val uramWReq  = IO(Vec(uramPorts, Decoupled(
    new AdvURAMReq(uramAddrBits, tlParms.sourceBits, 64))))
  val uramWData = IO(Output(Vec(uramPorts, UInt(64.W))))
  val uramWResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMWResp(uramAddrBits,  tlParms.sourceBits, 64))))))

  // Aligned Address to Bus Width
  val alignedAddr = tl.a.bits.address(uramAddrBits - 1 + 3, 3 + log2Ceil(uramPorts)) ## 0.U(log2Ceil(uramPorts).W)

  // Decode
  val tlGetVld = tl.a.valid && (tl.a.bits.opcode === Get)
  val tlPutFullVld = tl.a.valid && (tl.a.bits.opcode === PutFullData)
  val tlPutPartVld = tl.a.valid && (tl.a.bits.opcode === PutPartialData)

  // A Channel To RReq
  for(i <- 0 until uramPorts) {
    uramRReq(i).valid := tlGetVld
    uramRReq(i).bits.address := alignedAddr + i.U
    uramRReq(i).bits.id := tl.a.bits.source
    uramRReq(i).bits.byteEnable := 255.U
  }

  // A Channel To WReq
  for(i <- 0 until uramPorts) {
    uramWReq(i).valid := tlPutFullVld | tlPutPartVld
    uramWReq(i).bits.address := alignedAddr + i.U
    uramWReq(i).bits.id := tl.a.bits.source
    uramWReq(i).bits.byteEnable := tl.a.bits.mask.asTypeOf(Vec(uramPorts, UInt(8.W)))(i)
  }
  uramWData := tl.a.bits.data.asTypeOf(uramWData)

  // A Channel Ready
  tl.a.ready := (tlGetVld & uramRReq(0).ready) | ((!tlGetVld) & uramWReq(0).ready)

  // Resp To D Channel
  val accessAckData = Wire(DecoupledIO(new TLBundleD(tlParms)))
  accessAckData.valid := uramRResp(0).valid
  uramRResp(0).ready := accessAckData.ready
  accessAckData.bits.opcode := AccessAckData
  accessAckData.bits.param := 0.U
  accessAckData.bits.size := (log2Ceil(uramPorts) + 3).U
  accessAckData.bits.source := uramRResp(0).bits(0).id
  accessAckData.bits.sink := 0.U
  accessAckData.bits.denied := 0.B
  accessAckData.bits.corrupt := 0.B
  accessAckData.bits.data := VecInit(uramRResp(0).bits.map(_.data)).asTypeOf(accessAckData.bits.data)

  val accessAck = Wire(DecoupledIO(new TLBundleD(tlParms)))
  accessAck.valid := uramWResp(0).valid
  uramWResp(0).ready := accessAck.ready
  accessAck.bits.opcode := AccessAck
  accessAck.bits.param := 0.U
  accessAck.bits.size := (log2Ceil(uramPorts) + 3).U
  accessAck.bits.source := uramWResp(0).bits(0).id
  accessAck.bits.sink := 0.U
  accessAck.bits.denied := 0.B
  accessAck.bits.corrupt := 0.B
  accessAck.bits.data := DontCare

  // Arb
  val dArb = Module(new RRArbiter(new TLBundleD(tlParms), 2)).io
  dArb.in(0) <> Queue(accessAck, 2)
  dArb.in(1) <> Queue(accessAckData, 2)
  tl.d <> dArb.out
}
