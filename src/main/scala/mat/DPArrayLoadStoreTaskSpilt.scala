package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import uram.AdvURAMReq

class DPALoadStoreInnerCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMajor: Int, cMinor: Int) extends Bundle {
  val bank = Bool()
  val lastLoad = Bool()
  val baseUramAddr = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
  val pMajor = UInt(log2Ceil(cMajor).W) // pM <= sM
  val pMinor = UInt(log2Ceil(cMinor).W) // pN <= sN
  val bufferAddr = UInt(log2Ceil(storageRatio).W)
}

// Mat RAM Shape -> Address
/*
*       DIM0
*  --  --  --  --
*|  0   1   2   3 |D
*|  4   5   6   7 |I
*|  8   9  10  11 |M
*| 12  13  14  15 |1
*  --  --  --  --
*
*/

// cMajor == 32, cMinor == 8 / 16

class DPArrayLoadStoreTaskSpilt(
  storageRatio: Int, busWidth: Int, mnkWidth: Int,
  addressWidth: Int, cMNK: Int, cMajor: Int, cMinor: Int,
  storeTask: Boolean
) extends Module {
  val ports = busWidth / 8
  val cmdIn = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))

  // URAM Request Interface
  val uramReq = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))

  // cmdOut to DPArrayBuffer
  val cmdOut = IO(Decoupled(new DPALoadStoreInnerCommand(storageRatio, mnkWidth, addressWidth, cMajor, cMinor)))

  val cmd = RegEnable(cmdIn.bits, cmdIn.fire)
  val cmdValid = RegInit(0.B)

  // Aligned Major/Minor Shift
  val alignMajor = Reg(UInt(log2Ceil(storageRatio).W))
  val alignMinor = Reg(UInt(log2Ceil(storageRatio).W))
  val minorLeft = Reg(UInt(log2Ceil(ma).W))
  val lastAlignMajor = alignMajor === 0.U
  val lastAlignMinor = alignMinor === 0.U
  val cmdNextFinish = lastAlignMinor & lastAlignMajor & cmdOut.ready
  cmdIn.ready := cmdNextFinish | !cmdValid

  when(cmdIn.fire) {
    cmdValid := 1.B
  }.elsewhen(cmdNextFinish) {
    cmdValid := 0.B
  }

  // pMajor must be Already Aligned to cMajor
  chisel3.assert(cmdIn.bits.pMajor(log2Ceil(cMajor) - 1, 0) === (cMajor - 1).U)
  when(cmdIn.fire) {
    alignMajor := cmdIn.bits.pMajor(log2Ceil(storageRatio * cMajor) - 1, log2Ceil(cMajor))
    alignMinor := cmdIn.bits.pMinor(log2Ceil(storageRatio * cMinor) - 1, log2Ceil(cMinor))
    minorLeft  := cmdIn.bits.pMinor(log2Ceil(cMinor) - 1, 0)
  }.elsewhen(cmdOut.fire) {
    when(lastAlignMajor) {
      alignMajor := cmdIn.bits.pMajor(log2Ceil(storageRatio * cMajor) - 1, log2Ceil(cMajor))
      alignMinor := alignMinor(log2Ceil(storageRatio * cMinor) - 1, log2Ceil(cMinor)) - 1.U
      minorLeft  := (cMinor - 1).U
    }.otherwise {
      alignMajor := alignMajor(log2Ceil(storageRatio * cMajor) - 1, log2Ceil(cMajor)) - 1.U
    }
  }

  cmdOut.valid := cmdValid
  cmdOut.bits.bank := cmd.bank
  cmdOut.bits.baseUramAddr := cmd.baseAddr
  cmdOut.bits.baseDimMajor := Cat(alignMajor, 0.U(log2Ceil(cMajor).W)) + cmd.baseDimMajor
  cmdOut.bits.baseDimMinor := Cat(alignMinor, 0.U(log2Ceil(cMinor).W)) + cmd.baseDimMinor
  cmdOut.bits.pMajor := (cMajor - 1).U
  cmdOut.bits.pMinor := minorLeft
  cmdOut.bits.bufferAddr := alignMajor | (alignMinor << cmd.log2SDimMajor).asUInt

}

class DPALoadStoreMicroCommand(storageRatio: Int) extends Bundle {
  val bank = Bool()
  val lastLoad = Bool()
  val bufferAddr = UInt(log2Ceil(storageRatio).W)
}

class DPArrayLoadStoreExec(
  storageRatio: Int, busWidth: Int, mnkWidth: Int,
  addressWidth: Int, cMNK: Int, cMajor: Int, cMinor: Int,
  storeTask: Boolean) extends Module {
  val ports = busWidth / 8
  val elemWidth = if(storeTask) 4 else 1
  val cmdIn = IO(Flipped(Decoupled(new DPALoadStoreInnerCommand(storageRatio, mnkWidth, addressWidth, cMajor, cMinor))))

  val cmdQ = Queue(cmdIn)

  // URAM Address Computation Logic
  val uramAddr = VecInit.tabulate(cMinor, cMajor / elemWidth)((minor, major) => {

  })

  // URAM Request Interface
  val uramReq = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))

  if(storeTask) {

  } else {

  }

}
