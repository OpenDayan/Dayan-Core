package rvtng.bus

// See LICENSE.SiFive for license details.

import chisel3._
import chisel3.util._

class TilelinkUHToAXI(val tlParms: TLBundleParameters) extends Module {
  // tlWr can be mapped to AXI4 - AW+W+B, with no Burst Transaction, no ID reuse.
  val tlWr = IO(Flipped(new TLBundle(tlParms)))
  // tlRd can be mapped to AXI4 - AR+R, with no Burst Transaction, no ID reuse.
  val tlRd = IO(Flipped(new TLBundle(tlParms)))

  private val axi4Params = AXI4BundleParameters(tlParms.addressBits, tlParms.dataBits, tlParms.sourceBits)

  // AXI4
  val axi4 = IO(new AXI4Bundle(axi4Params))

  chisel3.assert(!(tlWr.a.valid & tlWr.a.bits.opcode =/= TLMessages.PutFullData))
  chisel3.assert(!(tlRd.a.valid & tlRd.a.bits.opcode =/= TLMessages.Get))

  axi4.ar.valid := tlRd.a.valid
  tlRd.a.ready  := axi4.ar.ready
  axi4.ar.bits.addr := tlRd.a.bits.address
  axi4.ar.bits.id   := tlRd.a.bits.source
  axi4.ar.bits.size := log2Ceil(tlParms.dataBits / 8).U
  axi4.ar.bits.len  := 0.U
  axi4.ar.bits.burst := 0.U

  private val awQ = Wire(axi4.aw.cloneType)
  private val wQ  = Wire(axi4.w.cloneType)

  axi4.aw <> Queue(awQ, 2)
  axi4.w  <> Queue(wQ, 2)

  tlWr.a.ready  := awQ.ready & wQ.ready

  awQ.valid := tlWr.a.valid & wQ.ready
  awQ.bits.addr := tlWr.a.bits.address
  awQ.bits.id   := tlWr.a.bits.source
  awQ.bits.size := log2Ceil(tlParms.dataBits / 8).U
  awQ.bits.len  := 0.U
  awQ.bits.burst := 0.U

  wQ.valid := tlWr.a.valid & awQ.ready
  wQ.bits.data  := tlWr.a.bits.data
  wQ.bits.strb  := tlWr.a.bits.mask
  wQ.bits.last  := 1.B

  tlRd.d.valid        := axi4.r.valid
  axi4.r.ready        := tlRd.d.ready
  tlRd.d.bits.data    := axi4.r.bits.data
  tlRd.d.bits.source  := axi4.r.bits.id
  tlRd.d.bits.opcode  := TLMessages.AccessAckData
  tlRd.d.bits.param   := 0.U
  tlRd.d.bits.size    := log2Ceil(tlParms.dataBits / 8).U
  tlRd.d.bits.sink    := 0.U
  tlRd.d.bits.denied  := 0.B // TODO: Connect to resp
  tlRd.d.bits.corrupt := 0.B

  tlWr.d.valid        := axi4.b.valid
  axi4.b.ready        := tlWr.d.ready
  tlWr.d.bits.data    := DontCare
  tlWr.d.bits.source  := axi4.b.bits.id
  tlWr.d.bits.opcode  := TLMessages.AccessAck
  tlWr.d.bits.param   := 0.U
  tlWr.d.bits.size    := log2Ceil(tlParms.dataBits / 8).U
  tlWr.d.bits.sink    := 0.U
  tlWr.d.bits.denied  := 0.B // TODO: Connect to resp
  tlWr.d.bits.corrupt := 0.B
}
