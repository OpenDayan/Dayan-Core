package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 16 >= MK 8 2
// B: 16 >= NK 4 2
// C: 32 >= MN 8 4
class MatDPACommand(storageRatio: Int) extends Bundle {
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
class DPArrayCtrl(storageRatio: Int) extends Module{
  val inst_i = IO(Flipped(Decoupled(new MatDPACommand(storageRatio))))

  val inst = RegEnable(inst_i.bits, inst_i.fire)
  val pMisOne = inst_i.bits.pM === 0.U
  val pNisOne = inst_i.bits.pN === 0.U
  val pMisTwo = inst_i.bits.pM === 1.U
  val pNisTwo = inst_i.bits.pN === 1.U
  val pMisThree = inst_i.bits.pM === 2.U
  val pNisThree = inst_i.bits.pN === 2.U

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
  val kStallCycle = RegEnable(nextKStallCycle, inst_i.fire)

  val lastInst = Wire(Bool())
  val nextBusy = Wire(Bool())
  val busy = RegNext(nextBusy)
  inst_i.ready := !busy | lastInst // Allowing Fully Pipeline Micro Instruction Handling

  when(inst_i.fire) {
    nextBusy := 1.B
  }.otherwise {
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

  when(inst_i.fire) {
    nextK := inst_i.bits.pK
    nextN := inst_i.bits.pN
    nextM := inst_i.bits.pM
    // TODO: Shuld we Stall between Multiple Requests ?
    nextKStall := 0.U
  }.otherwise {
    nextK := curK
    nextM := curM
    nextN := curN
    nextKStall := kStall
    when(kStall =/= 0.U) {
      nextKStall := kStall - 1.U
    }
    when(mSub1Grant) {
      when(nSub1Req) {nextM := inst.pM}.otherwise {nextM := curM - 1.U}
    }
    when(nSub1Grant) {
      when(kSub1Req) {nextN := inst.pN}.otherwise {nextN := curN - 1.U}
    }
    when(kSub1Grant) {
      nextKStall := kStallCycle
      when(!taskFinish) {nextK := curK - 1.U}
    }
  }

  // Controlling Interface To Matrix Register Tiles and DPArray
  val matValid = IO(Output(Bool()))
  val bf16 = IO(Output(Bool()))
  val aMatAddr = IO(Output(UInt((ptrW + 1).W)))
  val bMatAddr = IO(Output(UInt((ptrW + 1).W)))
  val cMatAddr = IO(Output(UInt((ptrW + 1).W)))

  matValid := busy & !kStall
  bf16 := inst.bf16
  // M, K
  aMatAddr := Cat(inst.aBank.asUInt, (curM << inst.log2SK) | curK)
  bMatAddr := Cat(inst.bBank.asUInt, (curN << inst.log2SK) | curK)
  cMatAddr := Cat(inst.cBank.asUInt, (curM << inst.log2SN) | curN)
}

object DPArrayCtrl extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayCtrl(32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayCtrl"
    )
  )
}

