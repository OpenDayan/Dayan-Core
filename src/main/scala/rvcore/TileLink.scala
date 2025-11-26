package rvcore

import chisel3._
import chisel3.util._

// Simplified TileLink Definitions for TL-C
// Reference: TileLink Spec 1.8

object TLMessages {
  // Channel A Opcodes
  val PutFullData    = 0.U(3.W)
  val PutPartialData = 1.U(3.W)
  val ArithmeticData = 2.U(3.W)
  val LogicalData    = 3.U(3.W)
  val Get            = 4.U(3.W)
  val Intent         = 5.U(3.W)
  val AcquireBlock   = 6.U(3.W)
  val AcquirePerm    = 7.U(3.W)

  // Channel B Opcodes
  val Probe          = 6.U(3.W) // 6 and 7 are used for Probe
  
  // Channel C Opcodes
  val AccessAck      = 0.U(3.W) // For ProbeAck effectively
  val AccessAckData  = 1.U(3.W)
  val HintAck        = 2.U(3.W)
  val ProbeAck       = 4.U(3.W)
  val ProbeAckData   = 5.U(3.W)
  val Release        = 6.U(3.W)
  val ReleaseData    = 7.U(3.W)

  // Channel D Opcodes
  val AccessAckD     = 0.U(3.W) // Renamed to avoid collision
  val AccessAckDataD = 1.U(3.W)
  val HintAckD       = 2.U(3.W)
  val Grant          = 4.U(3.W)
  val GrantData      = 5.U(3.W)
  val ReleaseAck     = 6.U(3.W)

  // Permissions
  val NtoB = 0.U(2.W)
  val NtoT = 1.U(2.W)
  val BtoT = 2.U(2.W)
  
  val TtoB = 0.U(2.W)
  val TtoN = 1.U(2.W)
  val BtoN = 2.U(2.W)
}

case class TLParams(
  addrBits: Int = 64,
  dataBits: Int = 64,
  sourceBits: Int = 4, // ID width
  sinkBits: Int = 4,
  sizeBits: Int = 4
)

class TLBundleA(params: TLParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val address = UInt(params.addrBits.W)
  val mask    = UInt((params.dataBits/8).W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

class TLBundleB(params: TLParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val address = UInt(params.addrBits.W)
  val mask    = UInt((params.dataBits/8).W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

class TLBundleC(params: TLParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val address = UInt(params.addrBits.W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

class TLBundleD(params: TLParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val sink    = UInt(params.sinkBits.W)
  val denied  = Bool()
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

class TLBundleE(params: TLParams) extends Bundle {
  val sink = UInt(params.sinkBits.W)
}

class TLBundle(params: TLParams) extends Bundle {
  val a = Decoupled(new TLBundleA(params))
  val b = Flipped(Decoupled(new TLBundleB(params))) // Master receives Probes
  val c = Decoupled(new TLBundleC(params))
  val d = Flipped(Decoupled(new TLBundleD(params))) // Master receives Grants/Resp
  val e = Decoupled(new TLBundleE(params))
}

