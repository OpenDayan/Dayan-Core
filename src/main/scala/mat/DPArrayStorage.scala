package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import uram._
import utils._

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

object DPArrayStorageCmd extends ChiselEnum {
  val writeA, writeB, writeC = Value
}

class DPArrayStorage(storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int, cM: Int, cN: Int, cK: Int) extends Module {
  val cMNK = Array(cM, cN, cK).max
  val ports = busWidth / 8
  // Cmd Interface
  val ldCmd = IO(Flipped(Decoupled(new MatLoadCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val stCmd = IO(Flipped(Decoupled(new MatStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  // Non-Blocking Cmd Interface
  val calcCmd = IO(Flipped(Decoupled(new DPAMicroCommand(storageRatio))))
  // URAM Interface
  val uramRreq  = IO(Vec(ports, Decoupled(new AdvURAMRReq(addressWidth, 0, 64))))
  val uramRresp = IO(Flipped(Vec(ports, new AdvURAMResp(addressWidth,   0, 64))))
  val uramWreq  = IO(Vec(ports, Decoupled(new AdvURAMWReq(addressWidth, 0, 64))))
}

object DPArrayStorage extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayStorage(32, 256, 20, 20, 16, 8, 32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayStorage"
    )
  )
}

