package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatTask(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  val baseAddr = UInt(addressWidth.W)
  val taskM = UInt(mnkWidth.W)
  val taskN = UInt(mnkWidth.W)
  val taskK = UInt(mnkWidth.W)
  val log2SubM = UInt(log2Ceil(storageRatio).W)
  val log2SubN = UInt(log2Ceil(storageRatio).W)
  val log2SubK = UInt(log2Ceil(storageRatio).W)
  val persistentMode = UInt(2.W)
  // 0 : M<-N<-K
  // 1 : K<-M<-N
  // 2 : N<-K<-M
}

class MatSubTask(storageRatio: Int, mnkWidth: Int, addressWidth: Int) extends Bundle {
  val baseAddr = UInt(addressWidth.W)
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
  val stCmd = IO(Decoupled(new MatLoadCommand(storageRatio, mnkWidth, addressWidth)))

  // load/calc/st Cmd Queue should NEVER Stall!
  chisel3.assert(!(ldCmd.valid & !ldCmd.ready))
  chisel3.assert(!(calcCmd.valid & !calcCmd.ready))
  chisel3.assert(!(stCmd.valid & !stCmd.ready))

  val ldComp = IO(Decoupled(Bool()))
  val crComp = IO(Decoupled(Bool()))
  val cwComp = IO(Decoupled(Bool()))
  val sComp = IO(Decoupled(Bool()))

  // ld/cr/cw/s Completion Queue should NEVER Stall!
  chisel3.assert(!(ldComp.valid & !ldComp.ready))
  chisel3.assert(!(crComp.valid & !crComp.ready))
  chisel3.assert(!(cwComp.valid & !cwComp.ready))
  chisel3.assert(!(sComp.valid & !sComp.ready))

  def getLowBits(value: UInt, bitsCnt: UInt, maxWidth: Int): UInt = {
    val mask = (2.U << bitsCnt) - 1.U
    val result = mask & value
    result(maxWidth - 1, 0)
  }

  // Input Task Queue
  val taskQ = Queue(task)
  // Split Task Queue
  val spTaskQ = Module(new Queue(new MatSubTask(storageRatio, mnkWidth, addressWidth), 2)) // Stallable, No Credit Based FC
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
  val fTaskQ  = Module(new Queue(Bool(), 2))
  val sCompQ4F  = Module(new Queue(Bool(), 2)) // Store Completion for Finish Stage
  val sCompQ4L  = Module(new Queue(Bool(), 2)) // Store Completion for Load Stage(Loading C should not issued before C wb)
  sCompQ4L.io.enq <> sComp
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
  taskQ.ready := !spTaskBusy | (spLastTask & spTaskQ.io.enq.ready)
  spTaskQ.io.enq.valid := !spTaskBusy
  spTaskQ.io.enq.bits.baseAddr := spTask.baseAddr
  spTaskQ.io.enq.bits.lastSubTask := spLastTask
  spTaskQ.io.enq.bits.subM := (spCurM >> spTask.log2SubM) << spTask.log2SubM
  spTaskQ.io.enq.bits.subN := (spCurN >> spTask.log2SubN) << spTask.log2SubN
  spTaskQ.io.enq.bits.subK := (spCurK >> spTask.log2SubK) << spTask.log2SubK
  spTaskQ.io.enq.bits.pM := spCurPM - 1.U
  spTaskQ.io.enq.bits.pN := spCurPN - 1.U
  spTaskQ.io.enq.bits.pK := spCurPK - 1.U
  spTaskQ.io.enq.bits.log2SubM := spTask.log2SubM
  spTaskQ.io.enq.bits.log2SubN := spTask.log2SubN
  spTaskQ.io.enq.bits.log2SubK := spTask.log2SubK
  val spCPersist = spTask.persistentMode === 0.U
  val spAPersist = spTask.persistentMode === 1.U
  val spBPersist = spTask.persistentMode === 2.U
  val spLastK = spTaskQ.io.enq.bits.subK === 0.U
  val spLastN = spTaskQ.io.enq.bits.subN === 0.U
  val spLastM = spTaskQ.io.enq.bits.subM === 0.U
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

  }.elsewhen(spTaskQ.io.enq.ready) {
    spTaskFirst := 0.B
    spTaskBusy := !spLastTask
    when(spCarryK) { when(spLastK) { spCurK := spTask.taskK }.otherwise { spCurK := spCurK - 1.U } }
    when(spCarryN) { when(spLastN) { spCurN := spTask.taskN }.otherwise { spCurN := spCurN - 1.U } }
    when(spCarryM) { when(spLastM) { spCurM := spTask.taskM }.otherwise { spCurM := spCurM - 1.U } }
  }
  spTaskQ.io.enq.bits.loadCnt := 2.U // 2 / 3
  when(spTaskFirst) { spTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstK & spCPersist) { spTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstN & spAPersist) { spTaskQ.io.enq.bits.loadCnt := 3.U }
  when(spFirstM & spBPersist) { spTaskQ.io.enq.bits.loadCnt := 3.U }
  spTaskQ.io.enq.bits.aLoad  := !spAPersist | spFirstM
  spTaskQ.io.enq.bits.bLoad  := !spBPersist | spFirstN
  spTaskQ.io.enq.bits.cLoad  := !spCPersist | spFirstK
  spTaskQ.io.enq.bits.cStore := !spCPersist | spLastK
  spTaskQ.io.enq.bits.aBank := spABank
  spTaskQ.io.enq.bits.bBank := spBBank
  spTaskQ.io.enq.bits.cBank := spCBank
  when(spTaskQ.io.enq.bits.aLoad & spTaskQ.io.enq.ready) { spABank := !spABank }
  when(spTaskQ.io.enq.bits.bLoad & spTaskQ.io.enq.ready) { spBBank := !spBBank }
  when(spTaskQ.io.enq.bits.cLoad & spTaskQ.io.enq.ready) { spCBank := !spCBank }

  // Load Pipeline
  // Receive: Spilt Task Request and Compute Read Completion
  // Issuing: Load Command and Compute Request
  val lFinish = Wire(Bool())
  spTaskQ.io.deq.valid := crCompQ.io.deq.ready & lFinish
  crCompQ.io.deq.valid := spTaskQ.io.deq.ready & lFinish
  chisel3.assert(spTaskQ.io.deq.fire === crCompQ.io.deq.fire)
  val lTaskFire = spTaskQ.io.deq.fire
  val lTaskValid  = RegInit(Bool(), 0.B)
  val lACmdFinish = RegInit(Bool(), 1.B)
  val lBCmdFinish = RegInit(Bool(), 1.B)
  val lCCmdFinish = RegInit(Bool(), 1.B)
  val lCmdLeft = Reg(UInt(2.W))
  val lACmd = Wire(new MatLoadCommand(storageRatio, mnkWidth, addressWidth))
  val lBCmd = Wire(new MatLoadCommand(storageRatio, mnkWidth, addressWidth))
  val lCCmd = Wire(new MatLoadCommand(storageRatio, mnkWidth, addressWidth))

  when(lTaskFire) {
    lACmdFinish := !spTaskQ.io.deq.bits.aLoad
    lBCmdFinish := !spTaskQ.io.deq.bits.bLoad
    lCCmdFinish := !spTaskQ.io.deq.bits.cLoad
    lCmdLeft := spTaskQ.io.deq.bits.loadCnt
  }.otherwise {
    when(ldCmd.ready) {
      when(!lCCmdFinish)        { lCCmdFinish := 1.B }
        .elsewhen(!lBCmdFinish) { lBCmdFinish := 1.B }
        .otherwise              { lACmdFinish := 1.B }
      when(lCmdLeft =/= 0.U) { lCmdLeft := lCmdLeft - 1.U}
    }
  }
  // Should not fire unused ldCmd
  chisel3.assert(!(lCmdLeft === 0.U & ldCmd.fire))
  ldCmd.bits := lACmd; ldCmd.valid := !lACmdFinish
  when(!lBCmdFinish) { ldCmd.bits := lBCmd; ldCmd.valid := 1.B }
  when(!lCCmdFinish) { ldCmd.bits := lCCmd; ldCmd.valid := 1.B }
  // No Bubble at all!
  lFinish := lCmdLeft === 0.U | (lCmdLeft === 1.U & ldCmd.ready)
  when(lTaskFire) {
    lTaskValid  := 1.B
  }.elsewhen(lFinish) {
    lTaskValid  := 0.B
  }

  cTaskQ.io.enq.valid := lTaskValid & lFinish

  // Compute Pipeline
  // Receive: Compute Request and Load Completion
  // Issuing: Compute Command, Store Request
//  val cReady = Wire(Bool())
  lCompQ.io.deq.valid := cTaskQ.io.deq.ready // & cReady
  cTaskQ.io.deq.valid := lCompQ.io.deq.ready // & cReady
  val cVld = cTaskQ.io.deq.ready & lCompQ.io.deq.ready
  calcCmd.valid := cVld
//  cReady := calcCmd.ready

  // Store Pipeline
  // Receive: Store Request and Compute Write Completion
  // Issuing: Finish Command
  val sReady = Wire(Bool())
  cwCompQ.io.deq.ready := sTaskQ.io.deq.valid
  sTaskQ.io.deq.ready := cwCompQ.io.deq.valid

  // Finish Pipeline
  // Receive: Finish Command, Store Completion
  // Issuing: Finish Completion

}
