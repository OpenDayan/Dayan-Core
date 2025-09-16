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

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 16 >= MK 8 2
// B: 16 >= NK 4 2
// C: 32 >= MN 8 4
class MatCalcCommand(storageRatio: Int) extends Bundle {
  // Compute Mode
  val bf16 = Bool()
  val aBank = Bool()
  val bBank = Bool()
  val cBank = Bool()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SM = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SN = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SK = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pM = UInt(log2Ceil(storageRatio).W) // pM <= sM
  val pN = UInt(log2Ceil(storageRatio).W) // pN <= sN
  val pK = UInt(log2Ceil(storageRatio).W) // pK <= sK
}


class MatLoadStoreCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  // Compute Mode
  val bank = Bool()
  val lastLoadAB = Bool()  // Need Completion, Last Load Command In A/B
  val waitLoadC  = Bool()  // Completion need to wait C Completion or not
  val ctrl = DPArrayStorageCmd()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SDimMajor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SDimMinor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pMajor = UInt(log2Ceil(storageRatio * cMNK).W) // pM / pN
  val pMinor = UInt(log2Ceil(storageRatio * cMNK).W) //      pK

  // Address Information
  val baseAddr = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
}

class DPArrayTop(
 storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int,
 cM: Int, cN: Int, cK: Int, sK: Int,
 wE: Int = 9, wF: Int = 25) extends Module {
  val cMNK = Array(cM, cN, cK).max
  val maxTileSize = Array(cM * cK, cN * cK, cM * cN * 4).max
  val maxBeat = maxTileSize / busWidth
  val ports = busWidth / 8

  // Load/Store Cmd Interface
  val ldCmd = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val stCmd = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))

  // Calculate  Cmd Interface
  val calcCmd = IO(Flipped(Decoupled(new MatCalcCommand(storageRatio))))

  // Completion Resp Interface
  val ldComp = IO(Decoupled(Bool()))
  val crComp = IO(Decoupled(Bool()))
  val cwComp = IO(Decoupled(Bool()))
  val stComp = IO(Decoupled(Bool()))

  // URAM Interface
  val rreq  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val rresp = IO(Flipped(Vec(ports, Decoupled(new AdvURAMResp(addressWidth,   0, 64)))))
  val wreq  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))

  // URAM Access MetaInfo Queue
  class URAMReadMeta extends Bundle {
    val ctrl = DPArrayStorageCmd()
    val bank = Bool()
    val addr = UInt(5.W)
    val beat = UInt(log2Ceil(maxBeat).W)
  }
  val uramAccessMetaQ = Module(new Queue(new URAMReadMeta, 32))

  // Non-Blocking calculating array
  val calcCore = Module(new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
    M = cM, N = cN, K = cK, sK = sK,
    debugFP32Output = true
  ))

  // Tensor Buffer
  val aBuffer = Module(new DPArrayBufferAB(busWidth, 32, cM, cK))
  val bBuffer = Module(new DPArrayBufferAB(busWidth, 32, cM, cK))
  val cBuffer = Module(new DPArrayBufferC (busWidth, 32, cM, cN, wE, wF))

  // Connecting wPort of all buffer to uram
  val flattenUramRData = VecInit.tabulate(ports)(i => rresp(i).bits.data).asTypeOf(Vec(busWidth, UInt(8.W)))
  aBuffer.wData    := flattenUramRData
  bBuffer.wData    := flattenUramRData
  cBuffer.extWData := flattenUramRData
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

