package mat

import chisel3._
import chisel3.util._
import utils.pipe
import _root_.circt.stage.ChiselStage

class DPACalcMicroCommand(storageRatio: Int) extends Bundle {
  val bf16 = Bool()
  val aMatAddr = UInt((1+log2Ceil(storageRatio)).W)
  val bMatAddr = UInt((1+log2Ceil(storageRatio)).W)
  val cMatAddr = UInt((1+log2Ceil(storageRatio)).W)
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

// K(pK) N(pN)

// Directly Controlling Module for FP8BF16FusedDPUnit
class DPArrayCalcTaskSpilt(storageRatio: Int, cwLatency: Int) extends Module{
  val cmdIn = IO(Flipped(Decoupled(new MatCalcCommand(storageRatio))))

  val cmd = RegEnable(cmdIn.bits, cmdIn.fire)
  val pMisOne = cmdIn.bits.pM === 0.U
  val pNisOne = cmdIn.bits.pN === 0.U
  val pMisTwo = cmdIn.bits.pM === 1.U
  val pNisTwo = cmdIn.bits.pN === 1.U
  val pMisThree = cmdIn.bits.pM === 2.U
  val pNisThree = cmdIn.bits.pN === 2.U

  val nextKStallCycle = Wire(UInt(2.W))
  nextKStallCycle := 0.U
  when(pMisOne & pNisOne) {
    nextKStallCycle := 3.U
  }.elsewhen(pMisOne | pNisOne) {
    when(pMisTwo | pNisTwo) {
      nextKStallCycle := 2.U
    }.elsewhen(pMisThree | pNisThree) {
      nextKStallCycle := 1.U
    }
  }
  val kStallCycle = RegEnable(nextKStallCycle, cmdIn.fire)

  val lastInst = Wire(Bool())
  val nextBusy = Wire(Bool())
  val busy = RegNext(nextBusy)
  cmdIn.ready := !busy | lastInst // Allowing Fully Pipeline Micro Instruction Handling

  nextBusy := busy
  when(cmdIn.fire) {
    nextBusy := 1.B
  }.elsewhen(busy) {
    nextBusy := !lastInst
  }

  val ptrW = log2Ceil(storageRatio)
  val nextK = Wire(UInt(ptrW.W))
  val nextN = Wire(UInt(ptrW.W))
  val nextM = Wire(UInt(ptrW.W))
  val nextKStall = Wire(UInt(2.W))

  val curK = RegNext(nextK)
  val curN = RegNext(nextN)
  val curM = RegNext(nextM)
  val mSub1Req = busy
  val nSub1Req = RegNext(nextM === 0.U)
  val kSub1Req = RegNext(nextM === 0.U & nextN === 0.U)
  val taskFinish = RegNext(nextM === 0.U & nextN === 0.U & nextK === 0.U & nextKStall === 0.U)
  lastInst := taskFinish

  val kStall = RegNext(nextKStall)
  val kSub1Grant = kStall === 0.U & kSub1Req
  val nSub1Grant = kStall === 0.U & nSub1Req
  val mSub1Grant = kStall === 0.U & mSub1Req
  
  nextKStall := kStall
  when(kStall =/= 0.U) {
    nextKStall := kStall - 1.U
  }
  when(kSub1Grant & busy) {
    nextKStall := kStallCycle
  }
  when(cmdIn.fire) {
    nextK := cmdIn.bits.pK
    nextN := cmdIn.bits.pN
    nextM := cmdIn.bits.pM
    // TODO: Shuld we Stall between Multiple Requests ?
    // nextKStall := 0.U
  }.otherwise {
    nextK := curK
    nextM := curM
    nextN := curN
    when(mSub1Grant) {
      when(nSub1Req) {nextM := cmd.pM}.otherwise {nextM := curM - 1.U}
    }
    when(nSub1Grant) {
      when(kSub1Req) {nextN := cmd.pN}.otherwise {nextN := curN - 1.U}
    }
    when(kSub1Grant) {
      when(!taskFinish) {nextK := curK - 1.U}
    }
  }

  // Controlling Interface To Matrix Register Tiles and DPArray
  val cmdOut = IO(Decoupled(new DPACalcMicroCommand(storageRatio)))

  cmdOut.valid := busy & !kStall
  cmdOut.bits.bf16 := cmd.bf16
  cmdOut.bits.aMatAddr := Cat(cmd.aBank.asUInt, (curM << cmd.log2SK) | curK)
  cmdOut.bits.bMatAddr := Cat(cmd.bBank.asUInt, (curN << cmd.log2SK) | curK)
  cmdOut.bits.cMatAddr := Cat(cmd.cBank.asUInt, (curM << cmd.log2SN) | curN)

  // Completion Interface To Top Controller
  val crComp = IO(Decoupled(Bool()))
  val cwComp = IO(Decoupled(Bool()))
  crComp.bits := 0.B
  crComp.valid := busy & lastInst

  val waitPipe = pipe.genPipe(busy & lastInst, cwLatency)
  cwComp.bits := 0.B
  cwComp.valid := waitPipe(cwLatency)
}

object DPArrayCalcTaskSpilt extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayCalcTaskSpilt(32, 9),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayCalcTaskSpilt"
    )
  )
}

