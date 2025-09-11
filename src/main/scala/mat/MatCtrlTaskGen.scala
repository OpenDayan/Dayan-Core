package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatTask(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  val bf16 = Bool()
  val aBaseAddr = UInt(addressWidth.W)
  val bBaseAddr = UInt(addressWidth.W)
  val cBaseAddr = UInt(addressWidth.W)
  val taskM = UInt(mnkWidth.W)
  val taskN = UInt(mnkWidth.W)
  val taskK = UInt(mnkWidth.W)
  val log2SubM = UInt(log2Ceil(storageRatio).W)
  val log2SubN = UInt(log2Ceil(storageRatio).W)
  val log2SubK = UInt(log2Ceil(storageRatio).W)
  val persistentMode = UInt(2.W)
  // PM0 : M<-N<-K
  // PM1 : K<-M<-N
  // PM2 : N<-K<-M
}

class MatSubTask(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  val bf16 = Bool()
  val aBaseAddr = UInt(addressWidth.W)
  val bBaseAddr = UInt(addressWidth.W)
  val cBaseAddr = UInt(addressWidth.W)
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
class MatCtrlTaskGen(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Module {
  val task = IO(Flipped(Decoupled(new MatTask(storageRatio, mnkWidth, addressWidth))))
  val ldCmd = IO(Decoupled(new MatLoadCommand(storageRatio, mnkWidth, addressWidth)))
  val calcCmd = IO(Decoupled(new MatDPACommand(storageRatio)))
  val stCmd = IO(Decoupled(new MatStoreCommand(storageRatio, mnkWidth, addressWidth)))

  // load/calc/st Cmd Queue should NEVER Stall!
  chisel3.assert(!(ldCmd.valid & !ldCmd.ready))
  chisel3.assert(!(calcCmd.valid & !calcCmd.ready))
  chisel3.assert(!(stCmd.valid & !stCmd.ready))

  val ldComp = IO(Flipped(Decoupled(Bool())))
  val crComp = IO(Flipped(Decoupled(Bool())))
  val cwComp = IO(Flipped(Decoupled(Bool())))
  val sComp = IO(Flipped(Decoupled(Bool())))
  val fComp = IO(Decoupled(Bool()))
  // ld/cr/cw/s Completion Queue should NEVER Stall!
  chisel3.assert(!(ldComp.valid & !ldComp.ready))
  chisel3.assert(!(crComp.valid & !crComp.ready))
  chisel3.assert(!(cwComp.valid & !cwComp.ready))
  chisel3.assert(!(sComp.valid & !sComp.ready))
  chisel3.assert(!(fComp.valid & !fComp.ready))

  def getLowBits(value: UInt, bitsCnt: UInt, maxWidth: Int): UInt = {
    val mask = (2.U << bitsCnt) - 1.U
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
  sCompQ4F.io.enq <> sComp
  sCompQ4L.io.enq.valid := sCompQ4F.io.enq.valid
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
  val spABank = RegInit(Bool(), 0.B)
  val spBBank = RegInit(Bool(), 0.B)
  val spCBank = RegInit(Bool(), 0.B)
  val spTask = RegEnable(taskQ.bits, taskQ.fire)
  val spCurM = Reg(UInt(mnkWidth.W))
  val spCurN = Reg(UInt(mnkWidth.W))
  val spCurK = Reg(UInt(mnkWidth.W))
  val spLastTask = Wire(Bool())
  val spCurPM = getLowBits(spCurM, spTask.log2SubM, 5)
  val spCurPN = getLowBits(spCurN, spTask.log2SubN, 5)
  val spCurPK = getLowBits(spCurK, spTask.log2SubK, 5)
  taskQ.ready := !spTaskBusy | (spLastTask & lTaskQ.io.enq.ready)
  lTaskQ.io.enq.valid := !spTaskBusy
  lTaskQ.io.enq.bits.bf16 := spTask.bf16
  lTaskQ.io.enq.bits.aBaseAddr := spTask.aBaseAddr
  lTaskQ.io.enq.bits.bBaseAddr := spTask.bBaseAddr
  lTaskQ.io.enq.bits.cBaseAddr := spTask.cBaseAddr
  lTaskQ.io.enq.bits.lastSubTask := spLastTask
  lTaskQ.io.enq.bits.subM := (spCurM >> spTask.log2SubM) << spTask.log2SubM
  lTaskQ.io.enq.bits.subN := (spCurN >> spTask.log2SubN) << spTask.log2SubN
  lTaskQ.io.enq.bits.subK := (spCurK >> spTask.log2SubK) << spTask.log2SubK
  lTaskQ.io.enq.bits.pM := spCurPM - 1.U
  lTaskQ.io.enq.bits.pN := spCurPN - 1.U
  lTaskQ.io.enq.bits.pK := spCurPK - 1.U
  lTaskQ.io.enq.bits.log2SubM := spTask.log2SubM
  lTaskQ.io.enq.bits.log2SubN := spTask.log2SubN
  lTaskQ.io.enq.bits.log2SubK := spTask.log2SubK
  val spCPersist = spTask.persistentMode === 0.U
  val spAPersist = spTask.persistentMode === 1.U
  val spBPersist = spTask.persistentMode === 2.U
  val spLastK = lTaskQ.io.enq.bits.subK === 0.U
  val spLastN = lTaskQ.io.enq.bits.subN === 0.U
  val spLastM = lTaskQ.io.enq.bits.subM === 0.U
  val spFirstK = spCurK === spTask.taskK
  val spFirstN = spCurN === spTask.taskN
  val spFirstM = spCurM === spTask.taskM
  val spCarryK = (spTask.persistentMode === 0.U) | spLastM
  val spCarryN = (spTask.persistentMode === 1.U) | spLastK
  val spCarryM = (spTask.persistentMode === 2.U) | spLastN
  spLastTask := spLastM & spLastN & spLastK
  when(taskQ.fire) {
    spTaskBusy := 1.B
    spTaskFirst := 1.B
    spCurM := taskQ.bits.taskM
    spCurN := taskQ.bits.taskN
    spCurK := taskQ.bits.taskK
  }.elsewhen(lTaskQ.io.enq.ready) {
    spTaskFirst := 0.B
    spTaskBusy := !spLastTask
    when(spCarryK) { when(spLastK) { spCurK := spTask.taskK }.otherwise { spCurK := spCurK - 1.U } }
    when(spCarryN) { when(spLastN) { spCurN := spTask.taskN }.otherwise { spCurN := spCurN - 1.U } }
    when(spCarryM) { when(spLastM) { spCurM := spTask.taskM }.otherwise { spCurM := spCurM - 1.U } }
  }
  lTaskQ.io.enq.bits.loadCnt := 2.U // 2 / 3
  when(spTaskFirst) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstK & spCPersist) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstN & spAPersist) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstM & spBPersist) { lTaskQ.io.enq.bits.loadCnt := 3.U }
  lTaskQ.io.enq.bits.aLoad  := !spAPersist | spFirstM
  lTaskQ.io.enq.bits.bLoad  := !spBPersist | spFirstN
  lTaskQ.io.enq.bits.cLoad  := !spCPersist | spFirstK
  lTaskQ.io.enq.bits.cStore := !spCPersist | spLastK
  lTaskQ.io.enq.bits.aBank := spABank
  lTaskQ.io.enq.bits.bBank := spBBank
  lTaskQ.io.enq.bits.cBank := spCBank
  when(lTaskQ.io.enq.bits.aLoad & lTaskQ.io.enq.ready) { spABank := !spABank }
  when(lTaskQ.io.enq.bits.bLoad & lTaskQ.io.enq.ready) { spBBank := !spBBank }
  when(lTaskQ.io.enq.bits.cLoad & lTaskQ.io.enq.ready) { spCBank := !spCBank }

  // Load Pipeline
  // Receive: Spilt Task Request and Compute Read Completion
  // Issuing: Load Command and Compute Request
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
  val lCmdLeft = RegInit(UInt(2.W), 0.U)
  val lACmd = Wire(new MatLoadCommand(storageRatio, mnkWidth, addressWidth))
  val lBCmd = Wire(new MatLoadCommand(storageRatio, mnkWidth, addressWidth))
  val lCCmd = Wire(new MatLoadCommand(storageRatio, mnkWidth, addressWidth))
  lACmd.ctrl := DPArrayStorageCmd.writeA
  lBCmd.ctrl := DPArrayStorageCmd.writeB
  lCCmd.ctrl := DPArrayStorageCmd.writeC
  lACmd.baseAddr := lTask.aBaseAddr
  lBCmd.baseAddr := lTask.bBaseAddr
  lBCmd.baseAddr := lTask.cBaseAddr
  lACmd.bank := lTask.aBank
  lBCmd.bank := lTask.bBank
  lCCmd.bank := lTask.cBank
  lACmd.lastLoad := 0.B
  lBCmd.lastLoad := !lTask.cLoad
  lCCmd.lastLoad := lTask.cLoad
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
    lCmdLeft := lTaskQ.io.deq.bits.loadCnt
  }.otherwise {
//    when(ldCmd.ready) { // Never Stalling
      when(!lACmdFinish)        { lACmdFinish := 1.B }
        .elsewhen(!lBCmdFinish) { lBCmdFinish := 1.B }
        .otherwise              { lCCmdFinish := lCCmdFinish | sCompQ4L.io.deq.valid }
//    }
    lCmdLeft := lCmdLeft - ldCmd.fire.asUInt
  }
  // Should not fire unused ldCmd
  chisel3.assert(!(lCmdLeft === 0.U & ldCmd.fire))
  ldCmd.bits := lCCmd; ldCmd.valid := !lCCmdFinish & sCompQ4L.io.deq.valid
  when(!lBCmdFinish) { ldCmd.bits := lBCmd; ldCmd.valid := 1.B } // Load B Secondly
  when(!lACmdFinish) { ldCmd.bits := lACmd; ldCmd.valid := 1.B } // Load A First
  sCompQ4L.io.deq.ready := lBCmdFinish & lBCmdFinish & !lCCmdFinish /* & ldCmd.ready */
  // No Bubble at all!
  lFinish := lCmdLeft === 0.U | (lCmdLeft === 1.U /* & ldCmd.ready */)
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
  calcCmd.bits.pM := cTask.pM
  calcCmd.bits.pN := cTask.pN
  calcCmd.bits.pK := cTask.pK
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
  stCmd.valid := sVld
  stCmd.bits.bank := sTask.cBank
  stCmd.bits.log2SDimMajor := sTask.log2SubM
  stCmd.bits.log2SDimMinor := sTask.log2SubN
  stCmd.bits.pMajor := sTask.pM
  stCmd.bits.pMinor := sTask.pN
  stCmd.bits.baseAddr := sTask.cBaseAddr
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
    new MatCtrlTaskGen(32, 16, 20),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatCtrlTaskGen"
    )
  )
}


