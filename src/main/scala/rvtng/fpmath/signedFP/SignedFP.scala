package rvtng.fpmath.signedFP

import chisel3._

// 内部浮点数
class SignedFP(wE: Int, wF: Int) extends Bundle {
  val exp = SInt(wE.W)
  val frac = SInt(wF.W)
}
