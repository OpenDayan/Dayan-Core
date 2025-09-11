package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils._
import uram._
class MatLoadCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  // Compute Mode
  val bank = Bool()
  val notify = Bool() // Need Completion, Last Load Command In a Sub-Task.
  val ctrl = DPArrayStorageCmd()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SDimMajor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SDimMinor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pMajor = UInt(log2Ceil(storageRatio).W) // pM <= sM
  val pMinor = UInt(log2Ceil(storageRatio).W) // pN <= sN

  // Address Information
  val baseAddr = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
}

// Receive MatCtrl Macro-Instruction to Fetch Blocks of A / B Mat From URAM and write To A/B Buffer of DPArray
class MatABCLoad(storageRatio: Int = 32) extends Module {
  val inst_i = IO(Flipped(Decoupled(new MatLoadCommand(storageRatio))))
  val uram_req = IO(Flipped(Decoupled(new Mat)))

}
