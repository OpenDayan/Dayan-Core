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

  val kShouldStall = RegEnable(pMisOne & pNisOne, inst_i.fire)
  val kShouldFwd = RegEnable((pMisOne | pNisOne) & (pMisTwo | pNisTwo | (pMisOne & pNisOne)), inst_i.fire)

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
  val nextKStall = Wire(Bool())
  val nextKFwd = Wire(Bool())

  val curK = RegNext(nextK)
  val curN = RegNext(nextN)
  val curM = RegNext(nextM)
  val mSub1Req = busy
  val nSub1Req = RegNext(nextM === 0.U)
  val kSub1Req = RegNext(nextM === 0.U & nextN === 0.U)
  val taskFinish = RegNext(nextM === 0.U & nextN === 0.U & nextK === 0.U & !nextKStall)
  lastInst := taskFinish

  val kStall = RegNext(nextKStall)
  val kFwd = RegNext(nextKFwd)
  val kSub1Grant = !kStall & kSub1Req
  val nSub1Grant = (!kStall | !kSub1Req) & nSub1Req
  val mSub1Grant = (!kStall | !kSub1Req | !nSub1Req) & mSub1Req

  when(inst_i.fire) {
    nextK := inst_i.bits.pK
    nextN := inst_i.bits.pN
    nextM := inst_i.bits.pM
    nextKStall := 0.B
    nextKFwd := 0.B
  }.otherwise {
    nextK := curK
    nextM := curM
    nextN := curN
    nextKFwd := kFwd
    nextKStall := kShouldStall & (!kStall)
    when(mSub1Grant) {
      when(nSub1Req) {nextM := inst.pM}.otherwise {nextM := curM - 1.U}
    }
    when(nSub1Grant) {
      when(kSub1Req) {nextN := inst.pN}.otherwise {nextN := curN - 1.U}
    }
    when(kSub1Grant) {
      nextKFwd := kShouldFwd
      when(!taskFinish) {nextK := curK - 1.U}
    }
  }

  // Controlling Interface To Matrix Register Tiles and DPArray
  val matValid = IO(Output(Bool()))
  val matFwd = IO(Output(Bool()))
  val aMatAddr = IO(Output(UInt((ptrW + 1).W)))
  val bMatAddr = IO(Output(UInt((ptrW + 1).W)))
  val cMatAddr = IO(Output(UInt((ptrW + 1).W)))

  matValid := busy & !kStall
  matFwd := kFwd
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

