package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatTask(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  val bf16 = Bool()
  val aBaseAddr = UInt(addressWidth.W)
  val aStride   = UInt(addressWidth.W)
  val bBaseAddr = UInt(addressWidth.W)
  val bStride   = UInt(addressWidth.W)
  val cBaseAddr = UInt(addressWidth.W)
  val cStride   = UInt(addressWidth.W)
  val taskM = UInt(mnkWidth.W)
  val taskN = UInt(mnkWidth.W)
  val taskK = UInt(mnkWidth.W)
  val log2SubM = UInt(log2Ceil(storageRatio).W)
  val log2SubN = UInt(log2Ceil(storageRatio).W)
  val log2SubK = UInt(log2Ceil(storageRatio).W)
  val persistentMode = UInt(2.W)
  // PM0 C Persist : M<-N<-K
  // PM1 A Persist : K<-M<-N
  // PM2 B Persist : N<-K<-M
}

class MatSubTask(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  val bf16 = Bool()
  val aBaseAddr = UInt(addressWidth.W)
  val aStride   = UInt(addressWidth.W)
  val bBaseAddr = UInt(addressWidth.W)
  val bStride   = UInt(addressWidth.W)
  val cBaseAddr = UInt(addressWidth.W)
  val cStride   = UInt(addressWidth.W)
  val lastSubTask = Bool()
  val subM = UInt(mnkWidth.W) // 子任务起点
  val subN = UInt(mnkWidth.W)
  val subK = UInt(mnkWidth.W)
  val pM = UInt(mnkWidth.W)   // 子任务尺寸
  val pN = UInt(mnkWidth.W)
  val pK = UInt(mnkWidth.W)
  val log2SubM = UInt(log2Ceil(1 + log2Ceil(storageRatio)).W) // 0 -> 1 1 -> 2 2 -> 4 3 -> 8 4 -> 16 5 -> 32
  val log2SubN = UInt(log2Ceil(1 + log2Ceil(storageRatio)).W)
  val log2SubK = UInt(log2Ceil(1 + log2Ceil(storageRatio)).W)

  val aBank = Bool()
  val aLoad = Bool()
  val bBank = Bool()
  val bLoad = Bool()
  val cBank = Bool()
  val cLoad = Bool()
  val cStore = Bool() // C should be write back to URAM

  val loadCnt = UInt(2.W)
}

// Note: This module heavily relies on credit based flow-control other than valid-ready flow-control.
// External Decoupled Interface should be connected to properly designed FIFO to prevent any overflow.
// calcCmd @2-Entry before Completion, Read Completion release 1 Entries
// stCmd @2-Entry before Completion,   Completion release 1 Entries
// ldCmd @6-Entry before Completion,   Completion release 2/3 Entries
class MatCtrlTaskGen(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cM: Int, cN: Int, cK: Int) extends Module {
  require(cK >= cM & cK >= cN) // MatLoadCommand.wMNK Should be the Max(cM, cN, cK)
  val task = IO(Flipped(Decoupled(new MatTask(storageRatio, mnkWidth, addressWidth))))
  val ldACmd = IO(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK)))
  val ldBCmd = IO(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK)))
  val ldCCmd = IO(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK)))
  val ldBar  = IO(Decoupled(Vec(3, Bool()))) // Ld Barrier
  val calcCmd = IO(Decoupled(new MatCalcCommand(storageRatio)))
  val stCmd = IO(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK)))
  val log2CM = log2Ceil(cM)
  val log2CN = log2Ceil(cN)
  val log2CK = log2Ceil(cK)
  val log2S = log2Ceil(storageRatio)

  // load/calc/st Cmd Queue should NEVER Stall!
  chisel3.assert(!(ldACmd.valid & !ldACmd.ready), "ldACmd OverFlow!")
  chisel3.assert(!(ldBCmd.valid & !ldBCmd.ready), "ldBCmd OverFlow!")
  chisel3.assert(!(ldCCmd.valid  & !ldCCmd.ready),  "ldCCmd OverFlow!")
  chisel3.assert(!(ldBar.valid  & !ldBar.ready),  "ldBar OverFlow!")
  chisel3.assert(!(calcCmd.valid & !calcCmd.ready), "calcCmd OverFlow!")
  chisel3.assert(!(stCmd.valid   & !stCmd.ready), "stCmd OverFlow!")

  val ldComp = IO(Flipped(Decoupled(Bool())))
  val crComp = IO(Flipped(Decoupled(Bool())))
  val cwComp = IO(Flipped(Decoupled(Bool())))
  val stComp = IO(Flipped(Decoupled(Bool())))
  val fComp = IO(Decoupled(Bool()))
  // ld/cr/cw/s Completion Queue should NEVER Stall!
  chisel3.assert(!(ldComp.valid & !ldComp.ready), "ldComp OverFlow!")
  chisel3.assert(!(crComp.valid & !crComp.ready), "crComp OverFlow!")
  chisel3.assert(!(cwComp.valid & !cwComp.ready), "cwComp OverFlow!")
  chisel3.assert(!(stComp.valid & !stComp.ready), "stComp OverFlow!")
  chisel3.assert(!(fComp.valid & !fComp.ready), "fComp OverFlow!")

  def getLowBits(value: UInt, bitsCnt: UInt, maxWidth: Int): UInt = {
    val mask = (1.U((maxWidth + 1).W) << bitsCnt) - 1.U
    val result = mask & value
    result(maxWidth - 1, 0)
  }

  // Input Task Queue
  val taskQ = Queue(task)
  // Split Task Queue
  val lTaskQ  = Module(new Queue(new MatSubTask(storageRatio, mnkWidth, addressWidth), 2)) // Stallable, No Credit Based FC
  // Compute Read Completion Queue
  val crCompQ = Module(new Queue(Bool(), 2))
  // Compute Task Queue
  val cTaskQ  = Module(new Queue(new MatSubTask(storageRatio, mnkWidth, addressWidth), 2))
  // Load Completion Queue
  val lCompQ  = Module(new Queue(Bool(), 2))
  // Store Task Queue
  val sTaskQ  = Module(new Queue(new MatSubTask(storageRatio, mnkWidth, addressWidth), 2))
  // Compute Write Completion Queue
  val cwCompQ = Module(new Queue(Bool(), 2))
  // Finish Command
  val fTaskQ  = Module(new Queue(new MatSubTask(storageRatio, mnkWidth, addressWidth), 2))
  // Fork Queue
  val sCompQ4F  = Module(new Queue(Bool(), 2)) // Store Completion for Finish Stage
  val sCompQ4L  = Module(new Queue(Bool(), 2)) // Store Completion for Load Stage(Loading C should not issued before C wb)

  lCompQ.io.enq <> ldComp
  sCompQ4F.io.enq <> stComp

  val compQInitializer = RegInit(2.U)
  when(compQInitializer =/= 0.U) {
    compQInitializer := compQInitializer - 1.U
  }
  crCompQ.io.enq.valid := crComp.valid | (compQInitializer =/= 0.U)
  crCompQ.io.enq.bits := crComp.bits
  crComp.ready := crCompQ.io.enq.ready
  cwCompQ.io.enq.valid := cwComp.valid
  cwCompQ.io.enq.bits := cwComp.bits
  cwComp.ready := cwCompQ.io.enq.ready
  sCompQ4L.io.enq.valid := sCompQ4F.io.enq.valid | (compQInitializer =/= 0.U)
  sCompQ4L.io.enq.bits := sCompQ4F.io.enq.bits
  // Compute Task Queue Should NEVER Stall!
  chisel3.assert(!(cTaskQ.io.enq.valid & !cTaskQ.io.enq.ready))
  // Store Task Queue Should NEVER Stall!
  chisel3.assert(!(sTaskQ.io.enq.valid & !sTaskQ.io.enq.ready))

  // Spilt Pipeline
  // Receive: Input Task Queue
  // Issuing: Split Task Queue
  val spTaskBusy = RegInit(Bool(), 0.B)
  val spTaskFirst = Reg(Bool())
  val spABank = RegInit(Bool(), 1.B)
  val spBBank = RegInit(Bool(), 1.B)
  val spCBank = RegInit(Bool(), 1.B)
  val spTask = RegEnable(taskQ.bits, taskQ.fire)
  val spCurM = Reg(UInt(mnkWidth.W))
  val spCurN = Reg(UInt(mnkWidth.W))
  val spCurK = Reg(UInt(mnkWidth.W))
  val spLastTask = Wire(Bool())
  val spCurPM = getLowBits(spCurM, spTask.log2SubM +& log2CM.U, log2S + log2CM)
  val spCurPN = getLowBits(spCurN, spTask.log2SubN +& log2CN.U, log2S + log2CN)
  val spCurPK = getLowBits(spCurK, spTask.log2SubK +& log2CK.U, log2S + log2CK)
  taskQ.ready := !spTaskBusy | (spLastTask & lTaskQ.io.enq.ready)
  lTaskQ.io.enq.valid := spTaskBusy
  lTaskQ.io.enq.bits.bf16 := spTask.bf16
  lTaskQ.io.enq.bits.aBaseAddr := spTask.aBaseAddr
  lTaskQ.io.enq.bits.aStride   := spTask.aStride
  lTaskQ.io.enq.bits.bBaseAddr := spTask.bBaseAddr
  lTaskQ.io.enq.bits.bStride   := spTask.bStride
  lTaskQ.io.enq.bits.cBaseAddr := spTask.cBaseAddr
  lTaskQ.io.enq.bits.cStride   := spTask.cStride
  lTaskQ.io.enq.bits.lastSubTask := spLastTask
  lTaskQ.io.enq.bits.subM := (spCurM >> (spTask.log2SubM + log2CM.U)) << (spTask.log2SubM + log2CM.U)
  lTaskQ.io.enq.bits.subN := (spCurN >> (spTask.log2SubN + log2CN.U)) << (spTask.log2SubN + log2CN.U)
  lTaskQ.io.enq.bits.subK := (spCurK >> (spTask.log2SubK + log2CK.U)) << (spTask.log2SubK + log2CK.U)
  lTaskQ.io.enq.bits.pM := spCurPM
  lTaskQ.io.enq.bits.pN := spCurPN
  lTaskQ.io.enq.bits.pK := spCurPK
  lTaskQ.io.enq.bits.log2SubM := spTask.log2SubM
  lTaskQ.io.enq.bits.log2SubN := spTask.log2SubN
  lTaskQ.io.enq.bits.log2SubK := spTask.log2SubK
  val spCPersist = spTask.persistentMode === 0.U
  val spAPersist = spTask.persistentMode === 1.U
  val spBPersist = spTask.persistentMode === 2.U
  val spLastK = lTaskQ.io.enq.bits.subK === 0.U
  val spLastN = lTaskQ.io.enq.bits.subN === 0.U
  val spLastM = lTaskQ.io.enq.bits.subM === 0.U
  val spFirstK = spCurK === spTask.taskK - 1.U
  val spFirstN = spCurN === spTask.taskN - 1.U
  val spFirstM = spCurM === spTask.taskM - 1.U
  val spCarryK, spCarryM, spCarryN = Wire(Bool())
  spCarryK := spCPersist | ((spBPersist | (spAPersist & spLastN)) & spLastM)
  spCarryN := spAPersist | ((spCPersist | (spBPersist & spLastM)) & spLastK)
  spCarryM := spBPersist | ((spAPersist | (spCPersist & spLastK)) & spLastN)
  spLastTask := spLastM & spLastN & spLastK
  when(taskQ.fire) {
    spTaskBusy := 1.B
    spTaskFirst := 1.B
    spCurM := taskQ.bits.taskM - 1.U
    spCurN := taskQ.bits.taskN - 1.U
    spCurK := taskQ.bits.taskK - 1.U
  }.elsewhen(spTaskBusy & lTaskQ.io.enq.ready) {
    spTaskFirst := 0.B
    spTaskBusy := !spLastTask
    when(spCarryK) { when(spLastK) { spCurK := spTask.taskK - 1.U }.otherwise {
      spCurK := lTaskQ.io.enq.bits.subK - 1.U
    }}
    when(spCarryN) { when(spLastN) { spCurN := spTask.taskN - 1.U }.otherwise {
      spCurN := lTaskQ.io.enq.bits.subN - 1.U
    }}
    when(spCarryM) { when(spLastM) { spCurM := spTask.taskM - 1.U }.otherwise {
      spCurM := lTaskQ.io.enq.bits.subM - 1.U
    }}
  }
  lTaskQ.io.enq.bits.loadCnt := 2.U // 2 / 3
  when(spTaskFirst) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstK & spCPersist) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstN & spAPersist) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstM & spBPersist) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  lTaskQ.io.enq.bits.aLoad  := !spAPersist | spFirstN
  lTaskQ.io.enq.bits.bLoad  := !spBPersist | spFirstM
  lTaskQ.io.enq.bits.cLoad  := !spCPersist | spFirstK
  lTaskQ.io.enq.bits.cStore := !spCPersist | spLastK
  lTaskQ.io.enq.bits.aBank := Mux(lTaskQ.io.enq.bits.aLoad, !spABank, spABank)
  lTaskQ.io.enq.bits.bBank := Mux(lTaskQ.io.enq.bits.bLoad, !spBBank, spBBank)
  lTaskQ.io.enq.bits.cBank := Mux(lTaskQ.io.enq.bits.cLoad, !spCBank, spCBank)
  when(lTaskQ.io.enq.bits.aLoad & lTaskQ.io.enq.fire) { spABank := !spABank }
  when(lTaskQ.io.enq.bits.bLoad & lTaskQ.io.enq.fire) { spBBank := !spBBank }
  when(lTaskQ.io.enq.bits.cLoad & lTaskQ.io.enq.fire) { spCBank := !spCBank }

  // Load Pipeline
  // Receive: Spilt Task Request and Compute Read Completion
  // Issuing: Load Command and Compute Request
  // TODO: Spilt LoadAB and LoadC
  val lFinish = Wire(Bool())
  lTaskQ.io.deq.ready := crCompQ.io.deq.valid & lFinish
  crCompQ.io.deq.ready := lTaskQ.io.deq.valid & lFinish
  chisel3.assert(lTaskQ.io.deq.fire === crCompQ.io.deq.fire)
  val lTaskFire = lTaskQ.io.deq.fire
  val lTask = RegEnable(lTaskQ.io.deq.bits, lTaskFire)
  val lTaskValid  = RegInit(Bool(), 0.B)
  val lACmdFinish = RegInit(Bool(), 1.B)
  val lBCmdFinish = RegInit(Bool(), 1.B)
  val lCCmdFinish = RegInit(Bool(), 1.B)
  val lBarFinish  = RegInit(Bool(), 1.B)
  val lCmdLeft = RegInit(UInt(2.W), 0.U)
  val lACmd = Wire(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK))
  val lBCmd = Wire(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK))
  val lCCmd = Wire(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cK))
  lACmd.ctrl := DPArrayStorageCmd.loadA
  lBCmd.ctrl := DPArrayStorageCmd.loadB
  lCCmd.ctrl := DPArrayStorageCmd.loadC
  lACmd.baseAddr := lTask.aBaseAddr
  lACmd.majorStride := lTask.aStride
  lBCmd.baseAddr := lTask.bBaseAddr
  lBCmd.majorStride := lTask.bStride
  lCCmd.baseAddr := lTask.cBaseAddr
  lCCmd.majorStride := lTask.cStride
  lACmd.bank := lTask.aBank
  lBCmd.bank := lTask.bBank
  lCCmd.bank := lTask.cBank
  ldBar.bits(0) := lTask.aLoad
  ldBar.bits(1) := lTask.bLoad
  ldBar.bits(2) := lTask.cLoad
  lACmd.pMajor := lTask.pM
  lACmd.pMinor := lTask.pK
  lBCmd.pMajor := lTask.pN
  lBCmd.pMinor := lTask.pK
  lCCmd.pMajor := lTask.pM
  lCCmd.pMinor := lTask.pN
  lACmd.baseDimMajor := lTask.subM
  lACmd.baseDimMinor := lTask.subK
  lBCmd.baseDimMajor := lTask.subN
  lBCmd.baseDimMinor := lTask.subK
  lCCmd.baseDimMajor := lTask.subM
  lCCmd.baseDimMinor := lTask.subN
  lACmd.log2SDimMajor := lTask.log2SubM
  lACmd.log2SDimMinor := lTask.log2SubK
  lBCmd.log2SDimMajor := lTask.log2SubN
  lBCmd.log2SDimMinor := lTask.log2SubK
  lCCmd.log2SDimMajor := lTask.log2SubM
  lCCmd.log2SDimMinor := lTask.log2SubN

  when(lTaskFire) {
    lACmdFinish := !lTaskQ.io.deq.bits.aLoad
    lBCmdFinish := !lTaskQ.io.deq.bits.bLoad
    lCCmdFinish := !lTaskQ.io.deq.bits.cLoad
    lBarFinish  := 0.B
    lCmdLeft := lTaskQ.io.deq.bits.loadCnt
  }.otherwise {
    when(!lACmdFinish)          { lACmdFinish := 1.B }
    when(!lBCmdFinish)          { lBCmdFinish := 1.B }
    when(sCompQ4L.io.deq.valid) { lCCmdFinish := 1.B }
    when(!lBarFinish)           { lBarFinish  := 1.B }
    lCmdLeft := lCmdLeft - ldACmd.fire.asUInt - ldBCmd.fire.asUInt - ldCCmd.fire.asUInt
  }
  // Should not fire unused ldCmd
  chisel3.assert(!(lCmdLeft === 0.U & ldACmd.fire))
  chisel3.assert(!(lCmdLeft === 0.U & ldBCmd.fire))
  chisel3.assert(!(lCmdLeft === 0.U & ldCCmd.fire))
  ldACmd.bits := lACmd; ldACmd.valid := !lACmdFinish
  ldBCmd.bits := lBCmd; ldBCmd.valid := !lBCmdFinish
  ldCCmd.bits := lCCmd; ldCCmd.valid := !lCCmdFinish & sCompQ4L.io.deq.valid
  ldBar.valid := !lBarFinish
  sCompQ4L.io.deq.ready := !lCCmdFinish /* & ldCmd.ready */
  // No Bubble at all!
  lFinish := lCCmdFinish | sCompQ4L.io.deq.valid
  when(lTaskFire) {
    lTaskValid  := 1.B
  }.elsewhen(lFinish) {
    lTaskValid  := 0.B
  }

  cTaskQ.io.enq.valid := lTaskValid & lFinish
  cTaskQ.io.enq.bits := lTask

  // Compute Pipeline
  // Receive: Compute Request and Load Completion
  // Issuing: Compute Command, Store Request
  lCompQ.io.deq.ready := cTaskQ.io.deq.valid // & cReady
  cTaskQ.io.deq.ready := lCompQ.io.deq.valid // & cReady
  val cVld = cTaskQ.io.deq.valid & lCompQ.io.deq.valid
  val cTask = cTaskQ.io.deq.bits
  calcCmd.valid := cVld
  calcCmd.bits.bf16 := cTask.bf16
  calcCmd.bits.aBank := cTask.aBank
  calcCmd.bits.bBank := cTask.bBank
  calcCmd.bits.cBank := cTask.cBank
  calcCmd.bits.log2SM := cTask.log2SubM
  calcCmd.bits.log2SN := cTask.log2SubN
  calcCmd.bits.log2SK := cTask.log2SubK
  calcCmd.bits.pM := cTask.pM(log2CM + log2S - 1, log2CM)
  calcCmd.bits.pN := cTask.pN(log2CN + log2S - 1, log2CN)
  calcCmd.bits.pK := cTask.pK(log2CK + log2S - 1, log2CK)
  sTaskQ.io.enq.valid := cVld
  sTaskQ.io.enq.bits := cTask
  //  val cReady = Wire(Bool())
  //  cReady := calcCmd.ready

  // Store Pipeline
  // Receive: Store Request and Compute Write Completion
  // Issuing: Finish Command
  cwCompQ.io.deq.ready := sTaskQ.io.deq.valid
  sTaskQ.io.deq.ready := cwCompQ.io.deq.valid
  val sVld = sTaskQ.io.deq.valid & cwCompQ.io.deq.valid
  val sTask = sTaskQ.io.deq.bits
  stCmd.valid := sVld & sTask.cStore
  stCmd.bits.bank := sTask.cBank
//  stCmd.bits.lastLoadAB := 0.B
//  stCmd.bits.waitLoadC := 0.B
  stCmd.bits.ctrl := DPArrayStorageCmd.loadC
  stCmd.bits.log2SDimMajor := sTask.log2SubM
  stCmd.bits.log2SDimMinor := sTask.log2SubN
  stCmd.bits.pMajor := sTask.pM
  stCmd.bits.pMinor := sTask.pN
  stCmd.bits.baseAddr     := sTask.cBaseAddr
  stCmd.bits.majorStride  := sTask.cStride
  stCmd.bits.baseDimMajor := sTask.subM
  stCmd.bits.baseDimMinor := sTask.subN
  fTaskQ.io.enq.valid := sVld
  fTaskQ.io.enq.bits := sTask

  // Finish Pipeline
  // Receive: Finish Command, Store Completion
  // Issuing: Finish Completion
  sCompQ4F.io.deq.ready := fTaskQ.io.deq.valid &  fTaskQ.io.deq.bits.cStore
  fTaskQ.io.deq.ready := sCompQ4F.io.deq.valid | !fTaskQ.io.deq.bits.cStore
  fComp.valid := fTaskQ.io.deq.bits.lastSubTask & fTaskQ.io.deq.fire
  fComp.bits  := 1.B

}

object MatCtrlTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatCtrlTaskGen(32, 16, 20,
      4, 2, 8),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatCtrlTaskGen"
    )
  )
}


