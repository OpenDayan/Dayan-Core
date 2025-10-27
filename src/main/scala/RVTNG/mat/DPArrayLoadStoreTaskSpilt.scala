package RVTNG.mat

import RVTNG.uram.AdvURAMReq
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class DPALoadStoreInnerCommand(storageRatio: Int, maxBeat: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  val bank = Bool()
  val isALoad = Bool()
  val isBLoad = Bool()
  val isCLoad = Bool()
  val beat = UInt(log2Ceil(maxBeat).W)
  val last = Bool()
  val baseUramAddr = UInt(addressWidth.W)
  val majorStride  = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
  val pMajor = UInt(log2Ceil(cMNK).W) // pM <= sM
  val pMinor = UInt(log2Ceil(cMNK).W) // pN <= sN
  val tileAddr = UInt((1 + log2Ceil(storageRatio)).W)
}

class DPALoadStoreTileMicroCommand(storageRatio: Int, maxBeat: Int, idWidth: Int) extends Bundle {
  val isALoad = Bool()
  val isBLoad = Bool()
  val isCLoad = Bool()
  val beat = UInt(log2Ceil(maxBeat).W)
  val last = Bool()
  val tileAddr = UInt((1+log2Ceil(storageRatio)).W)
}

class DPALoadStoreURAMMicroCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int, idWidth: Int) extends Bundle {
  val baseUramAddr = UInt(addressWidth.W)
  val majorStride  = UInt(addressWidth.W)
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinorByte = UInt(mnkWidth.W)
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

class DPArrayLoadStoreTaskSpilt(
  storageRatio: Int, busWidth: Int, mnkWidth: Int,
  addressWidth: Int, cMNK: Int, cM: Int, cN: Int, cK: Int,
  aTask: Boolean, bTask: Boolean, cTask: Boolean,
  storeTask: Boolean, bankSft: Int, idWidth: Int
) extends Module {
  val maxTileSize = if(aTask) {
    cM * cK
  } else if(bTask) {
    cN * cK
  }else {
    cM * cN * 4
  }

  val maxBeat = (maxTileSize + busWidth - 1) / busWidth
  val cmdIn = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val cmdTile = IO(Decoupled(new DPALoadStoreTileMicroCommand(storageRatio, maxBeat, idWidth)))
  val cmdURAM = IO(Decoupled(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth, addressWidth, cMNK, idWidth)))

  val cmdTileQ = Wire(Decoupled(new DPALoadStoreTileMicroCommand(storageRatio, maxBeat, idWidth)))
  val cmdURAMQ = Wire(Decoupled(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth, addressWidth, cMNK, idWidth)))
  cmdTile :<>= Queue(cmdTileQ, 2, false, false)
  cmdURAM :<>= Queue(cmdURAMQ, 2, false, false)

  val subCmd = Wire(Decoupled(new DPALoadStoreInnerCommand(storageRatio, maxBeat, mnkWidth, addressWidth, cMNK)))
  subCmd.ready := cmdTileQ.ready & cmdURAMQ.ready
  cmdTileQ.valid := subCmd.fire
  cmdURAMQ.valid := subCmd.fire

  val valid = RegInit(0.B)
  val cmd = RegEnable(cmdIn.bits, cmdIn.fire)
  val curMajor = Reg(UInt(log2Ceil(storageRatio * cMNK).W)) // Element Count
  val curMajorLeft = Reg(UInt(log2Ceil(cMNK).W))
  val curMinor = Reg(UInt(log2Ceil(storageRatio * cMNK).W)) // Element Count
  val curBeat  = Reg(UInt(log2Ceil(maxBeat).W))
  val lastMajor = curMajor === 0.U
  val lastMinor = curMinor === 0.U
  val lastBeat  = curBeat === 0.U
  val last = lastMajor & lastMinor & lastBeat

  when(cmdIn.fire) {
    valid := 1.B
    if(aTask) {
      curMajor := Cat(cmdIn.bits.pMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)), 0.U(log2Ceil(cM).W))
      if(maxBeat > 1) curBeat  := cmdIn.bits.pMajor(log2Ceil(cM) - 1, log2Ceil(cM) - log2Ceil(maxBeat))
      else curBeat  := 0.U
      curMajorLeft := cmdIn.bits.pMajor(log2Ceil(cM) - log2Ceil(maxBeat) - 1 , 0)
      curMinor := Cat(cmdIn.bits.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)), 0.U(log2Ceil(cK).W))
    } else if(bTask) {
      curMajor := Cat(cmdIn.bits.pMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)), 0.U(log2Ceil(cN).W))
      if(maxBeat > 1) curBeat := cmdIn.bits.pMajor(log2Ceil(cN) - 1, log2Ceil(cN) - log2Ceil(maxBeat))
      else curBeat  := 0.U
        curMajorLeft := cmdIn.bits.pMajor(log2Ceil(cN) - log2Ceil(maxBeat) - 1 , 0)
      curMinor := Cat(cmdIn.bits.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)), 0.U(log2Ceil(cK).W))
    } else {
      curMajor := Cat(cmdIn.bits.pMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)), 0.U(log2Ceil(cM).W))
      if(maxBeat > 1) curBeat  := cmdIn.bits.pMajor(log2Ceil(cM) - 1, log2Ceil(cM) - log2Ceil(maxBeat))
      else curBeat  := 0.U
      curMajorLeft := cmdIn.bits.pMajor(log2Ceil(cM) - log2Ceil(maxBeat) - 1 , 0)
      curMinor := Cat(cmdIn.bits.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)), 0.U(log2Ceil(cN).W))
    }
  }.elsewhen(subCmd.fire) {
    valid := !last
    when(lastMinor) {
      if(aTask) {
        curMinor := Cat(cmd.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)), 0.U(log2Ceil(cK).W))
        curMajorLeft := ((cM / maxBeat) - 1).U
      } else if(bTask) {
        curMinor := Cat(cmd.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)), 0.U(log2Ceil(cK).W))
        curMajorLeft := ((cN / maxBeat) - 1).U
      } else {
        curMinor := Cat(cmd.pMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)), 0.U(log2Ceil(cN).W))
        curMajorLeft := ((cM / maxBeat) - 1).U
      }
      when(lastBeat) {
        if(bTask) {
          curMajor := curMajor - cN.U
        } else {
          curMajor := curMajor - cM.U
        }
        curBeat  := (maxBeat - 1).U
      }.otherwise {
        curBeat  := curBeat - 1.U
      }
    }.otherwise {
      if(aTask | bTask) {
        curMinor := curMinor - cK.U
      } else {
        curMinor := curMinor - cN.U
      }
    }
  }
  cmdIn.ready := !valid | (subCmd.ready & last)

  val curLog2MajorSliceSize = if(aTask) {
    log2Ceil(cM / maxBeat).U
  } else if(bTask) {
    log2Ceil(cN / maxBeat).U
  } else {
    log2Ceil(cM / maxBeat).U
  }

  subCmd.valid        := valid
  subCmd.bits.bank    := cmd.bank
  subCmd.bits.isALoad := cmd.ctrl === DPArrayStorageCmd.loadA
  subCmd.bits.isBLoad := cmd.ctrl === DPArrayStorageCmd.loadB
  subCmd.bits.isCLoad := cmd.ctrl === DPArrayStorageCmd.loadC
  subCmd.bits.last    := last
  subCmd.bits.beat    := curBeat
  subCmd.bits.baseUramAddr := cmd.baseAddr
  subCmd.bits.majorStride  := cmd.majorStride
  subCmd.bits.baseDimMajor := cmd.baseDimMajor + curMajor  + (curBeat << curLog2MajorSliceSize)
  subCmd.bits.baseDimMinor := cmd.baseDimMinor + curMinor
  subCmd.bits.pMajor       := curMajorLeft
  if(aTask | bTask) {
    subCmd.bits.pMinor := (cK - 1).U
    if(aTask) {
      subCmd.bits.tileAddr := (cmd.bank.asUInt << bankSft).asUInt |
         curMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)).asUInt |
        (curMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)) << cmd.log2SDimMinor).asUInt
    } else {
      subCmd.bits.tileAddr := (cmd.bank.asUInt << bankSft).asUInt |
         curMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cK)).asUInt |
        (curMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)) << cmd.log2SDimMinor).asUInt
    }
  } else {
    subCmd.bits.pMinor := (cN - 1).U
    subCmd.bits.tileAddr := (cmd.bank.asUInt << bankSft).asUInt |
       curMinor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cN)).asUInt |
      (curMajor(log2Ceil(storageRatio * cMNK) - 1, log2Ceil(cM)) << cmd.log2SDimMinor).asUInt
  }

  cmdTileQ.bits.isALoad  := subCmd.bits.isALoad
  cmdTileQ.bits.isBLoad  := subCmd.bits.isBLoad
  cmdTileQ.bits.isCLoad  := subCmd.bits.isCLoad
  cmdTileQ.bits.beat     := subCmd.bits.beat
  cmdTileQ.bits.last     := subCmd.bits.last
  cmdTileQ.bits.tileAddr := subCmd.bits.tileAddr

  cmdURAMQ.bits.baseUramAddr := subCmd.bits.baseUramAddr
  cmdURAMQ.bits.majorStride  := subCmd.bits.majorStride
  cmdURAMQ.bits.baseDimMajor := subCmd.bits.baseDimMajor
  if(cTask) {
    cmdURAMQ.bits.baseDimMinorByte := Cat(subCmd.bits.baseDimMinor, 0.U(2.W))
  } else {
    cmdURAMQ.bits.baseDimMinorByte := subCmd.bits.baseDimMinor
  }
  cmdURAMQ.bits.pMinor := cmd.pMinor
  cmdURAMQ.bits.pMajor := cmd.pMajor
}

object DPArrayLoadStoreTaskSpilt extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayLoadStoreTaskSpilt(
      storageRatio = 32, busWidth = 256, mnkWidth = 12, addressWidth = 20,
      cMNK = 32, cM = 16, cN = 8, cK = 32, aTask = true, bTask = false, cTask = false,
      storeTask = false, bankSft = 4, idWidth = 5
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
