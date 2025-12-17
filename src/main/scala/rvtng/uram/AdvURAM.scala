package rvtng.uram

import chisel3._

// Advanced URAM:
//   Independent Address for each bank
//   Allowing Scatting / Gathering and Swizzling Access mode

// Maybe: Fused URAM with L1 Cache. Some part of URAM Can be Config to used as L1 Cache Instead of URAM

// Interface Define of AdvURAM
// Use ID To track Coming Port or other out-standing information.
class AdvURAMReq(val addrWidth: Int, val idWidth: Int, val dataWidth: Int) extends Bundle {
  val address = UInt(addrWidth.W)
  val id = UInt(idWidth.W)
  val byteEnable = UInt((dataWidth/8).W)
}

class AdvURAMResp(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()
  val data    = UInt(dataWidth.W)
  val id = UInt(idWidth.W)
}

class AdvURAMWResp(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()
  val id = UInt(idWidth.W)
}


