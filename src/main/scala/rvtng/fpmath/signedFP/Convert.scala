package rvtng.fpmath.signedFP

import chisel3._
import chisel3.util._
import rvtng.fpmath._
import hardfloat._

object SignedFPFrom {
  def apply(in: RawFloat): SignedFP = {
    val ret = Wire(new SignedFP(in.expWidth + 2, in.sigWidth + 1))
    val fracRaw = Cat(1.U(2.W), in.sig(in.sigWidth - 2, 0)).asSInt // sigWidth + 1 bits
    ret.exp := in.sExp - ((BigInt(1) << in.expWidth) - 1).S
    ret.frac := Mux(in.sign, -fracRaw, fracRaw)
    ret
  }
  def apply(expWidth: Int, sigWidth: Int, in: Bits): SignedFP = {
    SignedFPFrom(rawFloatFromFN(expWidth, sigWidth, in))
  }
}

object FNFromSignedFP {
  def apply(in: SignedFP): UInt = {
    val m = Module(new FPRoundingModule(in.wE, in.wF, 0))
    m.frac := in.frac.asUInt
    m.exp := Mux(in.frac === 0.S, (BigInt(1) << (in.wE - 1)).S(in.wE.W), in.exp)
    m.fp
  }
}

