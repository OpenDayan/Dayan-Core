package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import uram.AdvURAMReq

class DPALoadStoreInnerCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  val bank = Bool()
  val isBLoad = Bool()
  val lastLoad = Bool()
  val waitLoadC = Bool()
  val baseUramAddr = UInt(addressWidth.W)
  val majorStride  = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
  val pMajor = UInt(log2Ceil(cMNK).W) // pM <= sM
  val pMinor = UInt(log2Ceil(cMNK).W) // pN <= sN
  val bufferAddr = UInt((1 + log2Ceil(storageRatio)).W)
}

class DPALoadStoreMicroCommand(storageRatio: Int, maxBeat: Int) extends Bundle {
  val isBLoad = Bool()
  val beat = UInt(log2Ceil(maxBeat).W)
  val lastLoad = Bool()
  val waitLoadC = Bool()
  val bufferAddr = UInt((1+log2Ceil(storageRatio)).W)
}
class DPALoadStoreURAMMicroCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  val baseUramAddr = UInt(addressWidth.W)
  val majorStride  = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
  val pMajor = UInt(log2Ceil(cMNK).W) // pM <= sM
  val pMinor = UInt(log2Ceil(cMNK).W) // pN <= sN
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
class DPArrayLoadStoreBeatHandler(
  storageRatio: Int, busWidth: Int, mnkWidth: Int,
  addressWidth: Int, cMNK: Int, cM: Int, cN: Int, cK: Int,
  abTask: Boolean, cTask: Boolean, storeTask: Boolean) extends Module {
  val maxTileSize = if(abTask) {
    math.max(cM, cN) * cK
  } else {
    cM * cN * 4
  }
  val maxBeat = maxTileSize / busWidth
  require(maxBeat * busWidth == maxTileSize)
  val cmdIn = IO(Flipped(Decoupled(new DPALoadStoreInnerCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val cmdBuffer = IO(Decoupled(new DPALoadStoreMicroCommand(storageRatio, maxBeat)))
  val cmdURAM = IO(Decoupled(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth, addressWidth, cMNK)))

  val cmdQ = Queue(cmdIn)

  val cmd = RegEnable(cmdQ.bits, cmdQ.fire)
  val valid = RegInit(0.B)
  val firstBeat = Reg(Bool())
  val curLog2MajorSliceSize = if(abTask) {
    Mux(cmdQ.bits.isBLoad, log2Ceil(cN / (cN * cK / busWidth)).U, log2Ceil(cM / (cM * cK / busWidth)).U)
  } else {
    log2Ceil(cM / (cM * cN * 4 / busWidth)).U
  }
  val curBeat = Reg(UInt(log2Ceil(maxBeat).W))
  val finish = curBeat === 0.U
  val bothReady = cmdBuffer.ready & cmdURAM.ready
  cmdQ.ready := (bothReady & finish) | !valid

  when(cmdQ.fire) {
    valid := 1.B
    firstBeat := 1.B
    curBeat := cmdQ.bits.pMajor >> curLog2MajorSliceSize
  }.elsewhen(valid & bothReady) {
    valid := !finish
    curBeat := curBeat - 1.B
    firstBeat := 0.B
  }

  cmdBuffer.valid := valid & cmdURAM.ready
//  cmdBuffer.bits.bank := cmd.bank
  cmdBuffer.bits.isBLoad := cmd.isBLoad
  cmdBuffer.bits.beat := curBeat
  cmdBuffer.bits.lastLoad := cmd.lastLoad & finish
  cmdBuffer.bits.waitLoadC := cmd.waitLoadC
  cmdBuffer.bits.bufferAddr := cmd.bufferAddr
  cmdURAM.valid := valid & cmdBuffer.ready
  cmdURAM.bits.baseUramAddr := cmd.baseUramAddr
  cmdURAM.bits.majorStride  := cmd.majorStride
  cmdURAM.bits.baseDimMajor := cmd.baseDimMajor + (curBeat << curLog2MajorSliceSize)
  cmdURAM.bits.baseDimMinor := cmd.baseDimMinor
  cmdURAM.bits.pMinor := cmd.pMinor

  when(firstBeat) {
    if(abTask) {
      cmdURAM.bits.pMajor := Mux(cmd.isBLoad,
        cmd.pMajor(log2Ceil(cN / (cN * cK / busWidth)) - 1 , 0),
        cmd.pMajor(log2Ceil(cM / (cM * cK / busWidth)) - 1 , 0))
    } else {
      cmdURAM.bits.pMajor := cmd.pMajor(log2Ceil(cM / (cM * cN * 4 / busWidth)) - 1 , 0)
    }
  }.otherwise {
    if(abTask) {
    cmdURAM.bits.pMajor := Mux(cmd.isBLoad,
      (cN / (cN * cK / busWidth) - 1).U,
      (cM / (cM * cK / busWidth) - 1).U)
    } else {
      cmdURAM.bits.pMajor := (cM / (cM * cN * 4 / busWidth) - 1).U
    }
  }

}

class DPArrayLoadStoreTaskSpilt(
  storageRatio: Int, busWidth: Int, mnkWidth: Int,
  addressWidth: Int, cMNK: Int, cM: Int, cN: Int, cK: Int,
  abTask: Boolean, cTask: Boolean, storeTask: Boolean, bankSft: Int
) extends Module {
  val cmdIn = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val valid = RegInit(0.B)
  val cmd = RegEnable(cmdIn.bits, cmdIn.fire)
  val beatSpilt = Module(new DPArrayLoadStoreBeatHandler(
    storageRatio, busWidth, mnkWidth, addressWidth, cMNK, cM, cN, cK, abTask, cTask, storeTask))

  val cmdBuffer = IO(Decoupled(new DPALoadStoreMicroCommand(storageRatio, beatSpilt.maxBeat)))
  val cmdURAM = IO(Decoupled(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth, addressWidth, cMNK)))

  cmdBuffer :<>= Queue(beatSpilt.cmdBuffer, 4)
  cmdURAM :<>= Queue(beatSpilt.cmdURAM)

  val curMajor = Reg(UInt(log2Ceil(storageRatio * cMNK).W)) // Element Count
  val curMajorLeft = Reg(UInt(log2Ceil(cMNK).W))
  val curMinor = Reg(UInt(log2Ceil(storageRatio * cMNK).W)) // Element Count
  val lastMajor = curMajor === 0.U
  val lastMinor = curMinor === 0.U
  val last = lastMajor & lastMinor

  beatSpilt.cmdIn.valid := valid

  cmdIn.ready := !valid | (beatSpilt.cmdIn.ready & last)

  beatSpilt.cmdIn.bits.bank := cmd.bank
  beatSpilt.cmdIn.bits.isBLoad := cmd.ctrl === DPArrayStorageCmd.loadB
  beatSpilt.cmdIn.bits.lastLoad := (cmd.lastLoadAB | cTask.B) & last
  beatSpilt.cmdIn.bits.waitLoadC := cmd.waitLoadC
  beatSpilt.cmdIn.bits.baseUramAddr := cmd.baseAddr
  beatSpilt.cmdIn.bits.majorStride := cmd.majorStride
  beatSpilt.cmdIn.bits.baseDimMajor := cmd.baseDimMajor + curMajor
  beatSpilt.cmdIn.bits.baseDimMinor := cmd.baseDimMinor + curMinor
  beatSpilt.cmdIn.bits.pMajor := curMajorLeft
  if(cTask) {
    beatSpilt.cmdIn.bits.pMinor := (cN - 1).U
    beatSpilt.cmdIn.bits.bufferAddr :=
      (cmd.bank.asUInt << bankSft) |
      curMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)) |
      (curMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)) << cmd.log2SDimMinor).asUInt
  } else {
    beatSpilt.cmdIn.bits.pMinor := (cK - 1).U
    beatSpilt.cmdIn.bits.bufferAddr :=
      (cmd.bank.asUInt << bankSft) |
      curMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)) |
        Mux(cmd.ctrl === DPArrayStorageCmd.loadA,
        (curMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)) << cmd.log2SDimMinor).asUInt,
        (curMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)) << cmd.log2SDimMinor).asUInt)
  }

  when(cmdIn.fire) {
    valid := 1.B
    if(abTask) {
      curMajor := Mux(cmdIn.bits.ctrl === DPArrayStorageCmd.loadA,
        Cat(cmdIn.bits.pMajor(log2Ceil(storageRatio * cMNK) - 1 , log2Ceil(cM)), 0.U(log2Ceil(cM).W)),
        Cat(cmdIn.bits.pMajor(log2Ceil(storageRatio * cMNK) - 1 , log2Ceil(cN)), 0.U(log2Ceil(cN).W))
      )
      curMajorLeft := Mux(cmdIn.bits.ctrl === DPArrayStorageCmd.loadA,
        cmdIn.bits.pMajor(log2Ceil(cM) - 1 , 0),
        cmdIn.bits.pMajor(log2Ceil(cN) - 1 , 0)
      )
      curMinor := Cat(cmdIn.bits.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)), 0.U(log2Ceil(cK).W))
    } else {
      curMajor := Cat(cmdIn.bits.pMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)), 0.U(log2Ceil(cM).W))
      curMajorLeft := cmdIn.bits.pMajor(log2Ceil(cM) - 1 , 0)
      curMinor := Cat(cmdIn.bits.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)), 0.U(log2Ceil(cN).W))
    }
  }.elsewhen(beatSpilt.cmdIn.fire) {
    valid := !last
    when(lastMinor) {
      if(abTask) {
        curMajor := curMajor - Mux(cmd.ctrl === DPArrayStorageCmd.loadA, cM.U, cN.U)
        curMinor := Cat(cmd.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)), 0.U(log2Ceil(cK).W))
        curMajorLeft := Mux(cmd.ctrl === DPArrayStorageCmd.loadA, (cM - 1).U, (cN - 1).U)
      } else {
        curMajor := curMajor - cM.U
        curMinor := Cat(cmd.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)), 0.U(log2Ceil(cN).W))
        curMajorLeft := (cM - 1).U
      }
    }.otherwise {
      if(abTask) {
        curMinor := curMinor - cK.U
      } else {
        curMinor := curMinor - cN.U
      }
    }
  }
}

object DPArrayLoadStoreTaskSpilt extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayLoadStoreTaskSpilt(
      32, 256, 12, 20, 32, 16, 8, 32, true, false, false, 4
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayLoadStoreTaskSpilt"
    )
  )
}
