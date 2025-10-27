package RVTNG.mat

import RVTNG.uram.{Adv2R1WURAM, AdvURAMReq, AdvURAMResp, AdvURAMWResp}
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 16 >= MK 8 2
// B: 16 >= NK 4 2
// C: 32 >= MN 8 4

// Requirement for URAM:
//   busWidth === cK * AnyValue
//   busGroupSize === cK
class MatTop(
              storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int,
              cM: Int, cN: Int, cK: Int, kPerScale: Int, idWidth: Int = 5,
              wE: Int = 9, wF: Int = 25) extends Module {
  val matInst = IO(Flipped(Decoupled(new TensorInst)))
  val matInstResp = IO(Decoupled(UInt(64.W)))

  // Internal Configure Register
  // Note: problemK Should Align to cK
  val problemM = Reg(UInt(32.W))   // tcsrID: 0
  val problemN = Reg(UInt(32.W))   // tcsrID: 1
  val problemK = Reg(UInt(32.W))   // tcsrID: 2
  val strideA = Reg(UInt(32.W))    // tcsrID: 3
  val strideB = Reg(UInt(32.W))    // tcsrID: 4
  val strideC = Reg(UInt(32.W))    // tcsrID: 5
  val sM = RegInit(UInt(3.W), 2.U) // tcsrID: 6
  val sN = RegInit(UInt(3.W), 2.U) // tcsrID: 7
  val sK = RegInit(UInt(3.W), 2.U) // tcsrID: 8
  val persistentMode = RegInit(UInt(2.W), 0.U) // tcsrID: 9

  // Configure Instruction Handling
  val isCfgInst = matInst.bits.instWord(31, 25) === 0.U
  val isMatInst = matInst.bits.instWord(31, 25) === 0b0001000.U
  val isCfgWrite = isCfgInst & !matInst.bits.instWord(14)
  val tcsrID = matInst.bits.instWord(24, 15)

  when(isCfgWrite & matInst.fire) {
    when(tcsrID === 0.U) { problemM := matInst.bits.dst }
    when(tcsrID === 1.U) { problemN := matInst.bits.dst }
    when(tcsrID === 2.U) { problemK := matInst.bits.dst }
    when(tcsrID === 3.U) { strideA := matInst.bits.dst }
    when(tcsrID === 4.U) { strideB := matInst.bits.dst }
    when(tcsrID === 5.U) { strideC := matInst.bits.dst }
    when(tcsrID === 6.U) { sM := matInst.bits.dst }
    when(tcsrID === 7.U) { sN := matInst.bits.dst }
    when(tcsrID === 8.U) { sK := matInst.bits.dst }
    when(tcsrID === 9.U) { persistentMode := matInst.bits.dst }
  }
  // TODO: tcfg Read Handling
  matInstResp.bits := 0.U
  matInstResp.valid := 0.B
  // TODO: ScoreBoard Logic Handling

  // Mat Instruction Spilt logic
  val matSpilt = Module(new MatCtrlTaskGen(
    storageRatio, mnkWidth, addressWidth, cM, cN, cK
  ))
  matSpilt.task.valid := isMatInst & matInst.valid
  matInst.ready := isCfgInst | (isMatInst & matSpilt.task.ready)
  matSpilt.task.bits.bf16 := 0.B // Fixme: BF16/FP8
  matSpilt.task.bits.aBaseAddr := matInst.bits.src0
  matSpilt.task.bits.bBaseAddr := matInst.bits.src1
  matSpilt.task.bits.cBaseAddr := matInst.bits.dst
  matSpilt.task.bits.aStride := strideA
  matSpilt.task.bits.bStride := strideB
  matSpilt.task.bits.cStride := strideC
  matSpilt.task.bits.taskM := problemM
  matSpilt.task.bits.taskN := problemN
  matSpilt.task.bits.taskK := problemK << matSpilt.task.bits.bf16 // Scaled K if BF16
  matSpilt.task.bits.log2SubM := sM
  matSpilt.task.bits.log2SubN := sN
  matSpilt.task.bits.log2SubK := sK
  matSpilt.task.bits.persistentMode := persistentMode

  // DPArray Top
  val dpa = Module(new DPArrayTop(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cM = cM, cN = cN, cK = cK, sK = kPerScale, idWidth = idWidth, wE = wE, wF = wF, outStanding = 16
  ))

  dpa.ldACmd <> Queue(matSpilt.ldACmd, 8)
  dpa.ldBCmd <> Queue(matSpilt.ldBCmd, 8)
  dpa.ldCCmd <> Queue(matSpilt.ldCCmd, 8)
  dpa.ldBar  <> Queue(matSpilt.ldBar,  8)
  dpa.stCmd <> Queue(matSpilt.stCmd, 2)
  dpa.calcCmd <> Queue(matSpilt.calcCmd, 2)

  matSpilt.ldComp <> dpa.ldComp
  matSpilt.crComp <> dpa.crComp
  matSpilt.cwComp <> dpa.cwComp
  matSpilt.stComp <> dpa.stComp

  // TODO: Remove TEMP Finish Signals
  val finish = IO(Output(Bool()))
  finish := matSpilt.fComp.valid
  matSpilt.fComp.ready := 1.B

  // URAM Interface
  val ports = busWidth / 8
  val channelCount    = busWidth / cK
  val portsPerChannel = ports    / channelCount
  val rreq0  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val rreq1  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val wreq   = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val wdata  = IO(Output(Vec(ports, UInt(64.W))))
  val rresp0 = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMResp(addressWidth,  0, 64))))))
  val rresp1 = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMResp(addressWidth,  0, 64))))))
  val wresp  = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMWResp(addressWidth, 0, 64))))))
  rreq0 :<>= dpa.rreq0
  rreq1 :<>= dpa.rreq1
  wreq :<>= dpa.wreq
  wdata := dpa.wdata
  dpa.rresp0 :<>= rresp0
  dpa.rresp1 :<>= rresp1
  dpa.wresp  :<>= wresp
}

class MatTestTop(
              storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int,
              cM: Int, cN: Int, cK: Int, kPerScale: Int,
              wE: Int = 9, wF: Int = 25) extends Module {
  val mat = Module(new MatTop(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth,
    addressWidth = addressWidth, cM = cM, cN = cN, cK = cK, kPerScale = kPerScale,
    wE = wE, wF = wF))
  val ports = busWidth / 8
  // URAM Instance
  val uram = Module(new Adv2R1WURAM(
    R0Port = 2, R1Port = 1, W0Port = 2, GroupCount = cK / 8,
    fifoSize = 16, N = busWidth / 8, addrWidth = addressWidth, idWidth = 0, dataWidth = 64
  ))

  val matInst = IO(Flipped(Decoupled(new TensorInst)))
  val finish = IO(Output(Bool()))
  mat.matInst :<>= matInst
  mat.matInstResp.ready := 1.B
  finish := mat.finish
  uram.r0req_i(0) :<>= mat.rreq0
  uram.r1req_i(0) :<>= mat.rreq1
  uram.wreq_i(0)  :<>= mat.wreq
  uram.wdata_i(0) :<>= mat.wdata
  mat.rresp0 :<>= uram.r0resp_o(0)
  mat.rresp1 :<>= uram.r1resp_o(0)
  mat.wresp  :<>= uram.wresp_o(0)

  // Export uram Interface to prevent Vivado Optimize it.
  val uram_r  = IO(Flipped(Vec(busWidth / 8, Decoupled(new AdvURAMReq(addressWidth, 0, 64)))))
  val uram_w  = IO(Flipped(Vec(busWidth / 8, Decoupled(new AdvURAMReq(addressWidth, 0, 64)))))
  val uram_rr = IO(Vec((busWidth / 8) / (cK / 8),
    Decoupled(Vec(cK / 8, new AdvURAMResp(addressWidth, 0, 64)))))
  val uram_wdata = IO(Input(Vec(busWidth / 8, UInt(64.W))))
  val uram_wr = IO(Vec((busWidth / 8) / (cK / 8),
    Decoupled(Vec((cK / 8), new AdvURAMWResp(addressWidth, 0, 64)))))
  uram.r0req_i(1)  <> uram_r
  uram.wreq_i(1)   <> uram_w
  uram.r0resp_o(1) <> uram_rr
  uram.wresp_o(1)  <> uram_wr
  uram.wdata_i(1) := uram_wdata
}

object MatTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new MatTestTop(storageRatio = 32,
      busWidth = 256, mnkWidth = 20, addressWidth = 32,
      cM = 16, cN = 8, cK = 32, kPerScale = 32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatTestTop"
    )
  )
}


