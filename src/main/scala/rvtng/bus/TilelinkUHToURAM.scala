package rvtng.bus

import chisel3._
import chisel3.util._

class TilelinkUHToURAM(val tlParms: TLBundleParameters) extends Module {
  // tl can be mapped to URAM - R+RResp / W+WResp, with no Burst Transaction, no ID reuse.
  val tl = IO(Flipped(new TLBundle(tlParms)))


  // A Channel To RReq / WReq
  // Get / PutFull / PutPartial

  // Resp To D Channel


}
