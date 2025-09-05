package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatDPACommand(storageRatio: Int) extends Bundle {
  // Compute Mode
  val bf16 = Bool()
  val aBank = Bool()
  val bBank = Bool()
  val cBank = Bool()
  // Storage Shape
  val log2SM = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SN = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SK = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pM = UInt(log2Ceil(storageRatio).W)
  val pN = UInt(log2Ceil(storageRatio).W)
  val pK = UInt(log2Ceil(storageRatio).W)
}

// Directly Controlling Module for FP8BF16FusedDPUnit
class DPArrayCtrl extends Module{

}
