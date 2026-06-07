package rvtng.vpu

import rvtng.top._

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Note: Scoreboard write back can be reordered with CSR Instruction write back.
class VpuScoreboard(params: VpuParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  val instIssue  = IO(Input(Bool()))
  val instCommit = IO(Input(Bool()))

  private val maxOnTheFlyInst = 32
  private val log2MaxOnTheFlyInst = log2Ceil(maxOnTheFlyInst)
  private val isCounter = RegInit(0.U((log2MaxOnTheFlyInst + 2).W))
  private val cmCounter = RegInit(0.U((log2MaxOnTheFlyInst + 2).W))

  isCounter := isCounter + instIssue.asUInt
  cmCounter := cmCounter + instCommit.asUInt

  class ScoreBoardInst extends Bundle {
    val isCount = UInt((log2MaxOnTheFlyInst + 2).W)
    val isBar = Bool()
    val rd = UInt(5.W)
    val rdData = UInt(64.W)
  }

  private val sbInst = Wire(DecoupledIO(new ScoreBoardInst))
  private val isVecScoreboardBar = (instReq.bits.instWord(6, 0) === "h0b".U) &
                                   (instReq.bits.instWord(31, 27) === 0b00001.U) &
                                   (instReq.bits.instWord(26, 25) === 1.U) &
                                   (instReq.bits.instWord(24, 15) === 0.U) &
                                   (instReq.bits.instWord(14, 12) === 5.U)
  // Old 64-bit sb.bar compatibility is intentionally disabled.
  // private val isLegacyBar = instReq.bits.instWord(52)
  sbInst.valid := instReq.valid
  instReq.ready := sbInst.ready
  sbInst.bits.isCount := isCounter
  sbInst.bits.isBar   := isVecScoreboardBar
  sbInst.bits.rd      := instReq.bits.instWord(11, 7)
  sbInst.bits.rdData  := instReq.bits.rdData

  private val compQ = Queue(sbInst, 8)
  private val lowBitsCmp  = cmCounter(log2MaxOnTheFlyInst, 0) >= compQ.bits.isCount(log2MaxOnTheFlyInst, 0)
  private val highBitsCmp = cmCounter(log2MaxOnTheFlyInst + 1) === compQ.bits.isCount(log2MaxOnTheFlyInst + 1)
  private val hasCommit = Mux(highBitsCmp, lowBitsCmp, !lowBitsCmp)

  instResp.valid := compQ.valid & compQ.bits.isBar & hasCommit
  instResp.bits.rd := compQ.bits.rd
  instResp.bits.data := compQ.bits.rdData
  sbComp.valid := compQ.valid & !compQ.bits.isBar & hasCommit
  sbComp.bits.entryId := compQ.bits.rdData

  compQ.ready := hasCommit & Mux(compQ.bits.isBar, instResp.ready, sbComp.ready)

}
