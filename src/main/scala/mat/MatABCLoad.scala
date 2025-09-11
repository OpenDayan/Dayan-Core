package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils._
import uram._
class MatLoadCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  // Compute Mode
  val bank = Bool()
  val lastLoad = Bool() // Need Completion, Last Load Command In a Sub-Task.
  val ctrl = DPArrayStorageCmd()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SDimMajor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SDimMinor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pMajor = UInt(log2Ceil(storageRatio * cMNK).W) // pM <= sM
  val pMinor = UInt(log2Ceil(storageRatio * cMNK).W) // pN <= sN

  // Address Information
  val baseAddr = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
}

// Receive MatCtrl Macro-Instruction to Fetch Blocks of A / B Mat From URAM and write To A/B Buffer of DPArray
class MatABCLoad(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int, busWidth: Int = 256) extends Module {
  // Note: K Should be align to uram port width, 4B/8B
  val cmd = IO(Flipped(Decoupled(new MatLoadCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val uramReq = IO(Vec(busWidth / 8, Decoupled(new AdvURAMRReq(addressWidth, 0, 64))))
  val uramResp = IO(Flipped(Vec(busWidth / 8, Decoupled(new AdvURAMResp(addressWidth, 0, 64)))))
  val completion = IO(Flipped(Decoupled(Bool())))

}
