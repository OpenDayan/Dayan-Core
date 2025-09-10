package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.UltraRAM

// Advanced URAM:
//   Independent Address for each bank
//   Allowing Scatting / Gathering and Swizzling Access mode

// Maybe: Fused URAM with L1 Cache. Some part of URAM Can be Config to used as L1 Cache Instead of URAM

// Interface Define of AdvURAM
// Use ID To track Comming Port or other out-stading Informations.
class AdvURAMRReq(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val address = UInt(addrWidth.W)
  val id = UInt(idWidth.W)
}
class AdvURAMWReq(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val address = UInt(addrWidth.W)
  val data    = UInt(dataWidth.W)
  val id = UInt(idWidth.W)
}

class AdvURAMResp(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()
  val data    = UInt(dataWidth.W)
  val id = UInt(idWidth.W)
}

class AdvURAM {

}
