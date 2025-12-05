package rvtng.fpmath.signedFP

import chisel3._
import chisel3.util._
import rvtng.fpmath._
import hardfloat._

object SignedFPFrom {
  def apply(wE: Int, wF: Int, in: UInt): SignedFP = {
    val m = Module(new FPUnpackModule(wE, wF, 0))
    m.fp := in
    val ret = Wire(new SignedFP(8, 25))
    ret.exp := m.exp
    ret.frac := m.frac.asSInt
    ret
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

