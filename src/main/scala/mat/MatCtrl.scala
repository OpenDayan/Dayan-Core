package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 16 >= MK 8 2
// B: 16 >= NK 4 2
// C: 32 >= MN 8 4

class MatInst extends Bundle {
  val instWord = UInt(32.W)
  val src0 = UInt(64.W) // Based Address of A
  val src1 = UInt(64.W) // Based Address of B
  val dst  = UInt(64.W) // Based Address of C
}

class MatCtrl(cM: Int, cN: Int, cK: Int) extends Module {
  val matInst = IO(Flipped(Decoupled(new MatInst)))
  val matInstResp = IO(Decoupled(UInt(64.W)))
  val dpaCmd = IO(Decoupled(new MatCalcCommand(32)))

  // Internal Configure Register
  // Note: problemK Should Align to cK
  val problemM = Reg(UInt(32.W))   // tcsrID: 0
  val problemN = Reg(UInt(32.W))   // tcsrID: 1
  val problemK = Reg(UInt(32.W))   // tcsrID: 2
  val sM = RegInit(UInt(3.W), 2.U) // tcsrID: 3
  val sN = RegInit(UInt(3.W), 2.U) // tcsrID: 4
  val sK = RegInit(UInt(3.W), 2.U) // tcsrID: 5

  // Configure Instruction Handling
  val isCfgInst = matInst.bits.instWord(31, 25) === 127.U
  val isCfgWrite = isCfgInst & matInst.bits.instWord(12)
  val tcsrID = matInst.bits.instWord(23, 15)

  // TODO: tcfg Read Handling
  when(isCfgWrite & matInst.fire) {
    when(tcsrID === 0.U) { problemM := matInst.bits.dst }
    when(tcsrID === 1.U) { problemN := matInst.bits.dst }
    when(tcsrID === 2.U) { problemK := matInst.bits.dst }
    when(tcsrID === 3.U) { sM := matInst.bits.dst }
    when(tcsrID === 4.U) { sN := matInst.bits.dst }
    when(tcsrID === 5.U) { sK := matInst.bits.dst }
  }

  // Mat Instruction Spilt logic

//  matInst.ready := matInstResp.ready & (isCfgInst | (isMatInst & matInstFinished))

}
