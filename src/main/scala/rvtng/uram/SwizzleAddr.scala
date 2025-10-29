package rvtng.uram

import chisel3._
import chisel3.util._

object SwizzleAddr {
  def apply(addr: UInt, S: UInt, B: UInt, M: UInt, maxMask : Int = 4): UInt = {
    require(addr.getWidth > maxMask)
    val shiftedAddr = (addr >> S).asUInt
    val mask = Cat(0.U((addr.getWidth - maxMask).W), ((1.U(maxMask.W) << B).asUInt - 1.U).asUInt(addr.getWidth - 1, 0))
    val shiftedMask = (mask << M).asUInt
    addr ^ (shiftedAddr & shiftedMask)
  }
}
