package rvtng.fpmath.signedFP

import chisel3._
import chisel3.util._
import rvtng.fpmath._

object SignedFPNorm {
  def apply(in: SignedFP): SignedFP = {
    val ret = Wire(in.cloneType)
    // 绝对值
    val sign = in.frac(in.wF - 1)
    val frac = Mux(sign, -in.frac(in.wF - 2, 0), in.frac(in.wF - 2, 0))
    // 统计前导零数目
    val clz = CLZ(frac)
    ret.exp := in.exp - clz.asSInt
    ret.frac := in.frac << clz
    ret
  }
}
