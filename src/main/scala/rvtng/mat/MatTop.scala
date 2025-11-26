package rvtng.mat

import rvtng.uram.{Adv2R1WURAM, AdvURAMReq, AdvURAMResp, AdvURAMWResp}
import rvtng.top._

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

case class MatParams(storageRatio: Int = 32, busWidth: Int = 256, mnkWidth: Int = 20,
                     tcsrBanks: Int = 8,
                     addressWidth: Int = 20, cM: Int = 16, cN: Int = 8, cK: Int= 32,
                     kPerScale: Int = 32, idWidth: Int = 9,
                     wE: Int = 9, wF: Int = 25) {
}

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 32 >= MK 4 8
// B: 32 >= NK 4 8
// C: 32 >= MN 4 4

// Requirement for URAM:
//   busWidth === cK * AnyValue
//   busGroupSize === cK
class MatTop(params: MatParams) extends Module {
  val instReq  = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val sbComp   = IO(Decoupled(new ScoreboardComp()))
  val finish   = IO(Output(Bool()))

  // URAM Interface
  private val ports = params.busWidth / 8
  private val channelCount    = params.busWidth / params.cK
  private val portsPerChannel = ports    / channelCount
  val uramRReq0  = IO(Vec(ports, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64))))
  val uramRReq1  = IO(Vec(ports, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64))))
  val uramWReq   = IO(Vec(ports, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64))))
  val uramWData  = IO(Output(Vec(ports, UInt(64.W))))
  val uramRResp0 = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMResp(params.addressWidth,  0, 64))))))
  val uramRResp1 = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMResp(params.addressWidth,  0, 64))))))
  val uramWResp  = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMWResp(params.addressWidth, 0, 64))))))

  // Mat TCSR
  private val csr = Module(new MatControlStatusRegisters(params))

  // Mat Scoreboard
  private val sb = Module(new MatScoreboard(params))

  // Mat Instruction Spilt logic
  private val taskGen = Module(new MatCtrlTaskGen(
    params.storageRatio, params.mnkWidth, params.addressWidth, params.cM, params.cN, params.cK
  ))

  // DPArray Top
  private val dpa = Module(new DPArrayTop(
    storageRatio = params.storageRatio, busWidth = params.busWidth,
    mnkWidth = params.mnkWidth, addressWidth = params.addressWidth,
    cM = params.cM, cN = params.cN, cK = params.cK, sK = params.kPerScale,
    idWidth = params.idWidth, wE = params.wE, wF = params.wF, outStanding = 16
  ))

  // Instruction Decode
  private val decodeTaskSpilt = instReq.bits.instWord(31, 28) === 0b0001.U
  private val decodeCsrReq    = instReq.bits.instWord(31, 27) === 0b00000.U
  private val decodeCompReq   = instReq.bits.instWord(31, 27) === 0b00001.U
  private val decodeIllegal   = !(decodeTaskSpilt | decodeCsrReq | decodeCompReq)

  // Instruction Generation
  val curIsBF16 = csr.sel.ctrl.DataType === 0.U
  csr.sel.addr := instReq.bits.instWord(27, 25)
  taskGen.task.valid := instReq.valid & decodeTaskSpilt
  taskGen.task.bits.bf16 := curIsBF16
  taskGen.task.bits.aBaseAddr := instReq.bits.rs1Data
  taskGen.task.bits.bBaseAddr := instReq.bits.rs2Data
  taskGen.task.bits.cBaseAddr := instReq.bits.rdData
  taskGen.task.bits.aStride   := csr.sel.ctrl.AStride
  taskGen.task.bits.bStride   := csr.sel.ctrl.BStride
  taskGen.task.bits.cStride   := csr.sel.ctrl.CStride
  taskGen.task.bits.taskM     := csr.sel.ctrl.M
  taskGen.task.bits.taskN     := csr.sel.ctrl.N
  taskGen.task.bits.taskK     := Mux(curIsBF16, Cat(csr.sel.ctrl.K, 1.U(1.W)), csr.sel.ctrl.K)
  taskGen.task.bits.log2SubM  := 2.U
  taskGen.task.bits.log2SubN  := 2.U
  taskGen.task.bits.log2SubK  := 3.U
  taskGen.task.bits.ASwizzleMBS := csr.sel.ctrl.ASwizzleMBS
  taskGen.task.bits.BSwizzleMBS := csr.sel.ctrl.BSwizzleMBS
  taskGen.task.bits.CSwizzleMBS := csr.sel.ctrl.CSwizzleMBS
  taskGen.task.bits.persistentMode := csr.sel.ctrl.PersistentMode
  finish := taskGen.fComp.valid

  // SB
  sb.instReq.bits := instReq.bits
  sb.instReq.valid := instReq.valid & decodeCompReq
  sb.instIssue := taskGen.task.fire
  sb.instCommit := taskGen.fComp.valid
  sb.sbComp <> sbComp

  // CSR
  csr.instReq.bits  := instReq.bits
  csr.instReq.valid := instReq.valid & decodeCsrReq

  // instReq Ready
  instReq.ready := (decodeTaskSpilt & taskGen.task.ready) |
                   (decodeCsrReq    & csr.instReq.ready) |
                   (decodeCompReq   & sb.instReq.ready) | decodeIllegal

  chisel3.assert(!(instReq.valid & decodeIllegal))

  // Resp Arb
  private val respArb = Module(new RRArbiter(instResp.bits.cloneType, 2)).io
  instResp <> respArb.out
  respArb.in(0) <> csr.instResp
  respArb.in(1) <> sb.instResp

  dpa.ldACmd <> Queue(taskGen.ldACmd, 8)
  dpa.ldBCmd <> Queue(taskGen.ldBCmd, 8)
  dpa.ldCCmd <> Queue(taskGen.ldCCmd, 8)
  dpa.ldBar  <> Queue(taskGen.ldBar,  8)
  dpa.stCmd <> Queue(taskGen.stCmd, 2)
  dpa.calcCmd <> Queue(taskGen.calcCmd, 2)

  taskGen.ldComp <> dpa.ldComp
  taskGen.crComp <> dpa.crComp
  taskGen.cwComp <> dpa.cwComp
  taskGen.stComp <> dpa.stComp
  taskGen.fComp.ready := 1.B

  uramRReq0 :<>= dpa.rreq0
  uramRReq1 :<>= dpa.rreq1
  uramWReq :<>= dpa.wreq
  uramWData := dpa.wdata
  dpa.rresp0 :<>= uramRResp0
  dpa.rresp1 :<>= uramRResp1
  dpa.wresp  :<>= uramWResp
}

class MatTestTop(params: MatParams) extends Module {
  private val mat = Module(new MatTop(params))
  // URAM Instance
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 2, R1Port = 1, W0Port = 2, GroupCount = params.cK / 8,
    fifoSize = 16, N = params.busWidth / 8, addrWidth = params.addressWidth, idWidth = 0, dataWidth = 64
  ))

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val sbComp   = IO(Decoupled(new ScoreboardComp()))
  val finish = IO(Output(Bool()))
  mat.instReq <> instReq
  mat.instResp <> instResp
  mat.sbComp <> sbComp

  finish := mat.finish
  uram.r0req_i(0) :<>= mat.uramRReq0
  uram.r1req_i(0) :<>= mat.uramRReq1
  uram.wreq_i(0)  :<>= mat.uramWReq
  uram.wdata_i(0) :<>= mat.uramWData
  mat.uramRResp0 :<>= uram.r0resp_o(0)
  mat.uramRResp1 :<>= uram.r1resp_o(0)
  mat.uramWResp  :<>= uram.wresp_o(0)

  // Export uram Interface to prevent Vivado Optimize it.
  val uram_r  = IO(Flipped(Vec(params.busWidth / 8, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64)))))
  val uram_w  = IO(Flipped(Vec(params.busWidth / 8, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64)))))
  val uram_rr = IO(Vec((params.busWidth / 8) / (params.cK / 8),
    Decoupled(Vec(params.cK / 8, new AdvURAMResp(params.addressWidth, 0, 64)))))
  val uram_wdata = IO(Input(Vec(params.busWidth / 8, UInt(64.W))))
  val uram_wr = IO(Vec((params.busWidth / 8) / (params.cK / 8),
    Decoupled(Vec((params.cK / 8), new AdvURAMWResp(params.addressWidth, 0, 64)))))
  uram.r0req_i(1)  <> uram_r
  uram.wreq_i(1)   <> uram_w
  uram.r0resp_o(1) <> uram_rr
  uram.wresp_o(1)  <> uram_wr
  uram.wdata_i(1) := uram_wdata
}

object MatTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new MatTestTop(MatParams(storageRatio = 32,
      busWidth = 256, mnkWidth = 20, addressWidth = 32,
      cM = 16, cN = 8, cK = 32, kPerScale = 32)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatTestTop"
    )
  )
}


