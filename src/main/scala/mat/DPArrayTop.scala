package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import fpmath.{FP8BF16FusedDPArray, U8m2n2k1U4m2n2k2Mul}
import uram._
import utils._

object DPArrayStorageCmd extends ChiselEnum {
  val writeA, writeB, writeC = Value
}

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

class DPArrayTop(storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int, cM: Int, cN: Int, cK: Int, sK: Int) extends Module {
  val cMNK = Array(cM, cN, cK).max
  val ports = busWidth / 8
  // Cmd Interface
  val ldCmd = IO(Flipped(Decoupled(new MatLoadCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val stCmd = IO(Flipped(Decoupled(new MatStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  // Non-Blocking calculate Macro Cmd Interface
  val calcCmd = IO(Flipped(Decoupled(new DPAMicroCommand(storageRatio))))
  // URAM Interface
  val uramRreq  = IO(Vec(ports, Decoupled(new AdvURAMRReq(addressWidth, 0, 64))))
  val uramRresp = IO(Flipped(Vec(ports, new AdvURAMResp(addressWidth,   0, 64))))
  val uramWreq  = IO(Vec(ports, Decoupled(new AdvURAMWReq(addressWidth, 0, 64))))

  // Non-Blocking calculating array
  val calcCore = Module(new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
    M = cM, N = cN, K = cK, sK = sK,
    debugFP32Output = true
  ))

  // Tensor Buffer
  val aBuffer = Module(new DPArrayBufferAB(busWidth, 32, cM, cK))
  val bBuffer = Module(new DPArrayBufferAB(busWidth, 32, cM, cK))

}

object DPArrayTop extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayTop(32, 256, 20, 20, 16, 8, 32, 32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayStorage"
    )
  )
}

