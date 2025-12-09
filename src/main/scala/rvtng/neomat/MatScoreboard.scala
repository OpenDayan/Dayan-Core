package rvtng.neomat

import rvtng.top._

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Note: Scoreboard write back can be reordered with CSR Instruction write back.
class MatScoreboard(params: MatParams) extends Module {
  val io = IO(new Bundle{
      val instReq = Flipped(Decoupled(new TensorInst()))
      val instResp = Decoupled(new TensorResp())
      val sbComp  = Decoupled(new ScoreboardComp())

      val instIssue  = Input(Bool())
      val instCommit =  Input(Bool())
  })

  private val maxOnTheFlyInst = 32
  private val log2MaxOnTheFlyInst = log2Ceil(maxOnTheFlyInst)
  private val isCounter = RegInit(0.U((log2MaxOnTheFlyInst + 2).W))
  private val cmCounter = RegInit(0.U((log2MaxOnTheFlyInst + 2).W))

  isCounter := isCounter + io.instIssue.asUInt
  cmCounter := cmCounter + io.instCommit.asUInt

  class ScoreBoardInst extends Bundle {
    val isCount = UInt((log2MaxOnTheFlyInst + 2).W)
    val isBar = Bool()
    val rd = UInt(5.W)
    val rdData = UInt(64.W)
  }

  private val sbInst = Wire(DecoupledIO(new ScoreBoardInst))
  sbInst.valid := io.instReq.valid
  io.instReq.ready := sbInst.ready
  sbInst.bits.isCount := isCounter
  sbInst.bits.isBar   := io.instReq.bits.instWord(14)
  sbInst.bits.rd      := io.instReq.bits.instWord(11, 7)
  sbInst.bits.rdData  := io.instReq.bits.rdData

  private val compQ = Queue(sbInst, 8)
  private val lowBitsCmp  = cmCounter(log2MaxOnTheFlyInst, 0) >= compQ.bits.isCount(log2MaxOnTheFlyInst, 0)
  private val highBitsCmp = cmCounter(log2MaxOnTheFlyInst + 1) === compQ.bits.isCount(log2MaxOnTheFlyInst + 1)
  private val hasCommit = Mux(highBitsCmp, lowBitsCmp, !lowBitsCmp)

  io.instResp.valid := compQ.valid & compQ.bits.isBar & hasCommit
  io.instResp.bits.rd := compQ.bits.rd
  io.instResp.bits.data := compQ.bits.rdData
  io.sbComp.valid := compQ.valid & !compQ.bits.isBar & hasCommit
  io.sbComp.bits.entryId := compQ.bits.rdData

  compQ.ready := hasCommit & Mux(compQ.bits.isBar, io.sbComp.ready, io.instResp.ready)

}
