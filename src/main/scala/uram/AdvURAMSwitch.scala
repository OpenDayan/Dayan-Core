package uram

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// URAM Switch, switch Request based on low address bits
class AdvURAMSwitch(banks: Int, addrWidth: Int, idWidth: Int, dataWidth: Int) extends Module{
  val req_i = IO(Flipped(Vec(banks, Decoupled(new AdvURAMReq(addrWidth, idWidth, dataWidth)))))

}
