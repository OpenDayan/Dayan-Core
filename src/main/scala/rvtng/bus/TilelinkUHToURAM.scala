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

package dayan.rvtng.bus

import chisel3._
import chisel3.util._
import dayan.rvtng.bus.TLMessages._
import dayan.rvtng.uram._

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
  val tlPutVld = tl.a.valid && (tl.a.bits.opcode =/= Get)

  // R Meta FIFO
  val rreqMetaFIFO = Module(new Queue(new Bundle {
    val source = UInt(tlParms.sourceBits.W)
    val size   = UInt(tlParms.sizeBits.W)
  }, 32, false, false)).io
  rreqMetaFIFO.enq.valid := tlGetVld & uramRReq(0).ready
  rreqMetaFIFO.enq.bits.source := tl.a.bits.source
  rreqMetaFIFO.enq.bits.size   := tl.a.bits.size

  // A Channel To RReq
  for(i <- 0 until uramPorts) {
    uramRReq(i).valid := tlGetVld & rreqMetaFIFO.enq.ready
    uramRReq(i).bits.address := alignedAddr + i.U
    uramRReq(i).bits.id := DontCare
    uramRReq(i).bits.byteEnable := 255.U
  }

  // W Meta FIFO
  val wreqMetaFIFO = Module(new Queue(new Bundle {
    val source = UInt(tlParms.sourceBits.W)
    val size   = UInt(tlParms.sizeBits.W)
  }, 32, false, false)).io
  wreqMetaFIFO.enq.valid := tlPutVld & uramWReq(0).ready
  wreqMetaFIFO.enq.bits.source := tl.a.bits.source
  wreqMetaFIFO.enq.bits.size   := tl.a.bits.size
  // A Channel To WReq
  for(i <- 0 until uramPorts) {
    uramWReq(i).valid := tlPutVld & wreqMetaFIFO.enq.ready
    uramWReq(i).bits.address := alignedAddr + i.U
    uramWReq(i).bits.id := DontCare
    uramWReq(i).bits.byteEnable := tl.a.bits.mask.asTypeOf(Vec(uramPorts, UInt(8.W)))(i)
  }
  uramWData := tl.a.bits.data.asTypeOf(uramWData)

  // A Channel Ready
  tl.a.ready :=
  ((tl.a.bits.opcode === Get) & uramRReq(0).ready & rreqMetaFIFO.enq.ready) |
  ((tl.a.bits.opcode =/= Get) & uramWReq(0).ready & wreqMetaFIFO.enq.ready)

  // Resp To D Channel
  val accessAckData = Wire(DecoupledIO(new TLBundleD(tlParms)))
  accessAckData.valid := uramRResp(0).valid
  uramRResp(0).ready := accessAckData.ready
  accessAckData.bits.opcode := AccessAckData
  accessAckData.bits.param := 0.U
  accessAckData.bits.size := rreqMetaFIFO.deq.bits.size
  accessAckData.bits.source := rreqMetaFIFO.deq.bits.source
  accessAckData.bits.sink := 0.U
  accessAckData.bits.denied := 0.B
  accessAckData.bits.corrupt := 0.B
  accessAckData.bits.data := VecInit(uramRResp(0).bits.map(_.data)).asTypeOf(accessAckData.bits.data)
  rreqMetaFIFO.deq.ready := accessAckData.fire

  val accessAck = Wire(DecoupledIO(new TLBundleD(tlParms)))
  accessAck.valid := uramWResp(0).valid
  uramWResp(0).ready := accessAck.ready
  accessAck.bits.opcode := AccessAck
  accessAck.bits.param := 0.U
  accessAck.bits.size := wreqMetaFIFO.deq.bits.size
  accessAck.bits.source := wreqMetaFIFO.deq.bits.source
  accessAck.bits.sink := 0.U
  accessAck.bits.denied := 0.B
  accessAck.bits.corrupt := 0.B
  accessAck.bits.data := DontCare
  wreqMetaFIFO.deq.ready := accessAck.fire

  // Arb
  val dArb = Module(new RRArbiter(new TLBundleD(tlParms), 2)).io
  dArb.in(0) <> Queue(accessAck, 2)
  dArb.in(1) <> Queue(accessAckData, 2)
  tl.d <> dArb.out
}
