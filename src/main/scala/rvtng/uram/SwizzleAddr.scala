package rvtng.uram

import chisel3._
import chisel3.util._

object SwizzleAddr {
  def apply(addr: UInt, S: UInt, B: UInt, M: UInt, maxMask : Int = 8): UInt = {
    require(addr.getWidth > maxMask)
    val shiftedAddr = (addr >> S).asUInt(addr.getWidth - 1, 0)
    val mask = Cat(0.U((addr.getWidth - maxMask).W), ((1.U(maxMask.W) << B).asUInt - 1.U).asUInt(addr.getWidth - 1, 0))
    val shiftedMask = (mask << M).asUInt(addr.getWidth - 1, 0)
    addr ^ (shiftedAddr & shiftedMask)
  }
  def apply(addr: UInt, MBS: UInt, maxMask : Int = 8): UInt = {
    val M = MBS(23, 16)
    val B = MBS(15,  8)
    val S = MBS( 7,  0)
    SwizzleAddr(addr, S, B, M, maxMask)
  }
}
