package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils._
import uram._

class MatStoreCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  // Compute Mode
  val bank = Bool()
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

class MatCStore extends Module{

}
