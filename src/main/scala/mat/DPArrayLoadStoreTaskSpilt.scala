package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class DPALoadStoreMicroCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  val bank = Bool()
  val baseUramAddr = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
  val bufferAddr = UInt(log2Ceil(storageRatio).W)
  val log2SDimMajor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SDimMinor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val pMajor = UInt(log2Ceil(cMNK).W) // pM <= sM
  val pMinor = UInt(log2Ceil(cMNK).W) // pN <= sN
}

class DPArrayLoadStoreTaskSpilt(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Module {
  val cmdIn = IO(Flipped(Decoupled(new MatLoadCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val cmdOut = IO(Decoupled(new DPALoadStoreMicroCommand(storageRatio, mnkWidth, addressWidth, cMNK)))

  val cmd = RegEnable(cmdIn.bits, cmdIn.fire)

}
