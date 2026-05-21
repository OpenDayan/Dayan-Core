package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import rvtng.top.{ScoreboardComp, TensorInst, TensorResp}
import rvtng.uram._

case class MatParams(
  // 地址位宽
  val addrWidth: Int = 20,
  val MNKWidth: Int = 16,
  // 描述 DPArray 形状
  // 存驻比
  sM: Int = 2, sN: Int = 2, sK: Int = 16,
  // 运算器尺寸
  cM: Int = 8, cN: Int = 8, cK: Int = 32,
  // 内部精度：累加器
  wE: Int = 9, wF: Int = 25,
  // 总线参数
  busChannel: Int = 8, channelWidth: Int = 32, idWidth: Int = 9,
  // 写总线异构参数
  busWriteChannel: Int = 2,
  // TCSR Bank 个数
  tcsrBanks: Int = 8
) {
  // Tile 寄存器数量
  val log2SM = log2Ceil(sM)
  val log2SN = log2Ceil(sN)
  val log2SK = log2Ceil(sK)
  val log2CM = log2Ceil(cM)
  val log2CN = log2Ceil(cN)
  val log2CK = log2Ceil(cK)
  val countATile = sM * sK
  val countBTile = sN * sK
  val countCTile = sM * sN
  val countPETile = sM * sN * 2
  val log2CountATile = log2Ceil(countATile)
  val log2CountBTile = log2Ceil(countBTile)
  val log2CountCTile = log2Ceil(countCTile)
  val log2CountPETile = log2Ceil(countPETile)
}

// 注意：所有指令顺序执行顺序完成，因此返回信号采用脉冲就绪格式。
class MatTop(params: MatParams) extends Module {
  private val readPorts   = (params.busChannel * params.channelWidth) / 8
  private val readChannel = params.busChannel
  private val writePorts = (params.busWriteChannel * params.channelWidth) / 8
  private val writeChannel = params.busWriteChannel
  private val portsPerChannel = params.channelWidth / 8
  private val aTileSize = params.cM * params.cK
  private val bTileSize = params.cN * params.cK
  private val cTileSize = params.cM * params.cN * 4
  private val aTransactionCount = (aTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  private val log2ATransactionCount = log2Ceil(aTransactionCount)
  private val bTransactionCount = (bTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  private val log2BTransactionCount = log2Ceil(bTransactionCount)
  private val cTransactionCount = (cTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  private val log2CTransactionCount = log2Ceil(cTransactionCount)
  val io = IO(new Bundle {
    val instReq = Flipped(Decoupled(new TensorInst))
    val instResp = Decoupled(new TensorResp)
    val sbComp = Decoupled(new ScoreboardComp())
    val finish = Output(Bool())

    // URAM Interface
    val uramRReq0 = Vec(readPorts, Decoupled(new AdvURAMReq(params.addrWidth, params.idWidth, 64)))
    val uramRReq1 = Vec(readPorts, Decoupled(new AdvURAMReq(params.addrWidth, params.idWidth, 64)))
    val uramWReq = Vec(writePorts, Decoupled(new AdvURAMReq(params.addrWidth, params.idWidth, 64)))
    val uramWData = Output(Vec(writePorts, UInt(64.W)))
    val uramRResp0 = Flipped(Vec(readChannel, Decoupled(Vec(portsPerChannel,
      new AdvURAMResp(params.addrWidth, params.idWidth, 64)))))
    val uramRResp1 = Flipped(Vec(readChannel, Decoupled(Vec(portsPerChannel,
      new AdvURAMResp(params.addrWidth, params.idWidth, 64)))))
    val uramWResp = Flipped(Vec(writeChannel, Decoupled(Vec(portsPerChannel,
      new AdvURAMWResp(params.addrWidth, params.idWidth, 64)))))
  })

  // MAT CSR
  // Mat TCSR
  private val csr = Module(new MatControlStatusRegisters(params)).io

  // Mat Scoreboard
  private val sb = Module(new MatScoreboard(params)).io

  // Mat Task Generator
  private val tgen = Module(new MatTaskGen(params)).io

  // Mat Post-Process
  private val pp = Module(new MatPostProcess(params)).io

  // URAM Switch-0 (A/C Read)
  private val us0 = Module(new MatMemLoadTaskIssue(params, 2, params.log2CountATile,
    math.max(log2ATransactionCount, log2CTransactionCount))).io

  // URAM Switch-1 (B Read)
  private val us1 = Module(new MatMemLoadTaskIssue(params, 1, params.log2CountBTile, log2BTransactionCount)).io

  // URAM Switch-2 (C Write)
  private val us2 = Module(new MatMemStoreTaskIssue(params)).io

  // Mat Exec
  private val exe = Module(new MatExec(params)).io

  // 互联部分
  // 1. Input Instruction -> tgen / tcsr
  private val decodeTaskSpilt = io.instReq.bits.instWord(31, 28) === 0b0001.U
  private val decodeCsrReq    = io.instReq.bits.instWord(31, 27) === 0b00000.U
  private val decodeCompReq   = io.instReq.bits.instWord(31, 27) === 0b00001.U
  private val decodeIllegal   = !(decodeTaskSpilt | decodeCsrReq | decodeCompReq)

  // SB
  sb.instReq.bits := io.instReq.bits
  sb.instReq.valid := io.instReq.valid & decodeCompReq
  sb.instIssue := tgen.cmdIn.fire
  sb.instCommit := pp.finish
  sb.sbComp <> io.sbComp
  io.finish := pp.finish

  // CSR
  csr.instReq.bits  := io.instReq.bits
  csr.instReq.valid := io.instReq.valid & decodeCsrReq

  // instReq Ready
  io.instReq.ready := (decodeTaskSpilt & tgen.cmdIn.ready) |
    (decodeCsrReq    & csr.instReq.ready) |
    (decodeCompReq   & sb.instReq.ready) | decodeIllegal

  chisel3.assert(!(io.instReq.valid & decodeIllegal))

  // Resp Arb
  private val respArb = Module(new RRArbiter(io.instResp.bits.cloneType, 2)).io
  io.instResp <> respArb.out
  respArb.in(0) <> csr.instResp
  respArb.in(1) <> sb.instResp

  // mat tgen cmdIn generation
  private val curIsBF16 = csr.sel.ctrl.DataType === 0.U
  private val taskMode = io.instReq.bits.instWord(14, 12)//mode: mm=000, mma=001, conv2d=010, conv2da=011
  private val taskIsConv = taskMode(1)
  private val taskAccumulate = taskMode(0)
  csr.sel.addr := io.instReq.bits.instWord(27, 25)
  tgen.cmdIn.valid := io.instReq.valid & decodeTaskSpilt
  tgen.cmdIn.bits.bf16 := curIsBF16
  tgen.cmdIn.bits.opMode := taskMode
  tgen.cmdIn.bits.isConv := taskIsConv
  tgen.cmdIn.bits.accumulate := taskAccumulate
  tgen.cmdIn.bits.aBaseAddr := io.instReq.bits.rs1Data
  tgen.cmdIn.bits.persistentMode := csr.sel.ctrl.PersistentMode
  tgen.cmdIn.bits.taskCi := csr.sel.ctrl.taskCi
  tgen.cmdIn.bits.taskWi := csr.sel.ctrl.taskWi
  tgen.cmdIn.bits.taskHi := csr.sel.ctrl.taskHi
  tgen.cmdIn.bits.taskWo := csr.sel.ctrl.taskWo
  tgen.cmdIn.bits.taskHo := csr.sel.ctrl.taskHo
  tgen.cmdIn.bits.taskWk := csr.sel.ctrl.taskWk
  tgen.cmdIn.bits.taskHk := csr.sel.ctrl.taskHk
  tgen.cmdIn.bits.taskCo := csr.sel.ctrl.taskCo
  tgen.cmdIn.bits.wiStride := csr.sel.ctrl.wiStride
  tgen.cmdIn.bits.hiStride := csr.sel.ctrl.hiStride
  tgen.cmdIn.bits.wkStride := csr.sel.ctrl.wkStride
  tgen.cmdIn.bits.hkStride := csr.sel.ctrl.hkStride
  tgen.cmdIn.bits.coStride := csr.sel.ctrl.coStride
  tgen.cmdIn.bits.woStride := csr.sel.ctrl.woStride
  tgen.cmdIn.bits.hoStride := csr.sel.ctrl.hoStride
  tgen.cmdIn.bits.scaleWiStride := csr.sel.ctrl.ScaleWiStride
  tgen.cmdIn.bits.scaleHiStride := csr.sel.ctrl.ScaleHiStride
  tgen.cmdIn.bits.scaleWkStride := csr.sel.ctrl.ScaleWkStride
  tgen.cmdIn.bits.scaleHkStride := csr.sel.ctrl.ScaleHkStride
  tgen.cmdIn.bits.scaleCoStride := csr.sel.ctrl.ScaleCoStride
  tgen.cmdIn.bits.padL := csr.sel.ctrl.padL
  tgen.cmdIn.bits.padU := csr.sel.ctrl.padU
  tgen.cmdIn.bits.dilationW := csr.sel.ctrl.dilationW
  tgen.cmdIn.bits.dilationH := csr.sel.ctrl.dilationH
  tgen.cmdIn.bits.strideW := csr.sel.ctrl.strideW
  tgen.cmdIn.bits.strideH := csr.sel.ctrl.strideH
  tgen.cmdIn.bits.strideDilationW := csr.sel.ctrl.StrideDilationW
  tgen.cmdIn.bits.strideDilationH := csr.sel.ctrl.StrideDilationH
  tgen.cmdIn.bits.aBaseAddr := io.instReq.bits.rs1Data
  tgen.cmdIn.bits.bBaseAddr := io.instReq.bits.rs2Data
  tgen.cmdIn.bits.cBaseAddr := io.instReq.bits.rdData
  tgen.cmdIn.bits.aStride   := csr.sel.ctrl.AStride
  tgen.cmdIn.bits.bStride   := csr.sel.ctrl.BStride
  tgen.cmdIn.bits.cStride   := csr.sel.ctrl.CStride
  tgen.cmdIn.bits.taskM     := csr.sel.ctrl.M
  tgen.cmdIn.bits.taskN     := csr.sel.ctrl.N
  tgen.cmdIn.bits.taskK     := Mux(curIsBF16, Cat(csr.sel.ctrl.K, 1.U(1.W)), csr.sel.ctrl.K)
  tgen.cmdIn.bits.ASwizzleMBS := csr.sel.ctrl.ASwizzleMBS
  tgen.cmdIn.bits.BSwizzleMBS := csr.sel.ctrl.BSwizzleMBS
  tgen.cmdIn.bits.CSwizzleMBS := csr.sel.ctrl.CSwizzleMBS
  tgen.cmdIn.bits.persistentMode := csr.sel.ctrl.PersistentMode

  // tgen connection
  tgen.ldA        <> exe.ldA
  tgen.ldAAck     := exe.ldAAck
  tgen.ldARelease := exe.ldARelease
  tgen.ldB        <> exe.ldB
  tgen.ldBAck     := exe.ldBAck
  tgen.ldBRelease := exe.ldBRelease
  tgen.calc       <> exe.calc

  // pp connection
  pp.cmdIn   <> tgen.pp
  pp.ppAlloc <> tgen.ppAlloc
  pp.result  <> exe.result

  // mem connection
  exe.busAReq  <> us0.busRReq(0)
  exe.busAResp := us0.busRResp(0)
  exe.busAAck  := us0.busRAck(0)
  exe.busBReq  <> us1.busRReq(0)
  exe.busBResp := us1.busRResp(0)
  exe.busBAck  := us1.busRAck(0)
  pp.busRReq   <> us0.busRReq(1)
  pp.busRResp  := us0.busRResp(1)
  pp.busWReq   <> us2.busWReq
  pp.busWResp  := us2.busWResp

  // uram connection
  us0.uramReq   <> io.uramRReq0
  us0.uramResp  <> io.uramRResp0
  us1.uramReq   <> io.uramRReq1
  us1.uramResp  <> io.uramRResp1
  us2.uramWReq  <> io.uramWReq
  io.uramWData  := us2.uramWdata
  us2.uramWResp <> io.uramWResp
}


class MatTestTop(params: MatParams) extends Module {
  private val mat = Module(new MatTop(params)).io
  // URAM Instance
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 1, R1Port = 1, W0Port = 1, GroupCount = params.cK / 8,
    fifoSize = 16, N = (params.busChannel * params.channelWidth) / 8, addrWidth = params.addrWidth,
    idWidth = params.idWidth, dataWidth = 64
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
  mat.uramRResp0 :<>= uram.r0resp_o(0)
  mat.uramRResp1 :<>= uram.r1resp_o(0)
  private val ports = (params.busChannel * params.channelWidth) / 8
  private val writePorts = (params.busWriteChannel * params.channelWidth) / 8
  for(i <- 0 until ports) {
    if(i < writePorts) {
      uram.wreq_i(0)(i) <> mat.uramWReq(i)
      uram.wdata_i(0)(i) := mat.uramWData(i)
    } else {
      uram.wreq_i(0)(i).valid := 0.B
      uram.wreq_i(0)(i).bits := DontCare
      uram.wdata_i(0)(i) := DontCare
    }
  }
  for(i <- 0 until params.busChannel) {
    if(i < params.busWriteChannel) {
      mat.uramWResp(i) <> uram.wresp_o(0)(i)
    } else {
      uram.wresp_o(0)(i).ready := 1.B
    }
  }

  // Export uram Interface to prevent Vivado Optimize it.
//  val uram_r  = IO(Flipped(Vec(params.busWidth / 8, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64)))))
//  val uram_w  = IO(Flipped(Vec(params.busWidth / 8, Decoupled(new AdvURAMReq(params.addressWidth, 0, 64)))))
//  val uram_rr = IO(Vec((params.busWidth / 8) / (params.cK / 8),
//    Decoupled(Vec(params.cK / 8, new AdvURAMResp(params.addressWidth, 0, 64)))))
//  val uram_wdata = IO(Input(Vec(params.busWidth / 8, UInt(64.W))))
//  val uram_wr = IO(Vec((params.busWidth / 8) / (params.cK / 8),
//    Decoupled(Vec((params.cK / 8), new AdvURAMWResp(params.addressWidth, 0, 64)))))
//  uram.r0req_i(1)  <> uram_r
//  uram.wreq_i(1)   <> uram_w
//  uram.r0resp_o(1) <> uram_rr
//  uram.wresp_o(1)  <> uram_wr
//  uram.wdata_i(1) := uram_wdata
}

object MatTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new MatTestTop(MatParams(
      addrWidth = 20, MNKWidth = 16, sM = 2, sN = 2, sK = 16, cM = 16, cN = 8, cK = 32,
      wE = 9, wF = 25, busChannel = 8, channelWidth = 32, idWidth = 9,
      busWriteChannel = 2, tcsrBanks = 8
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatTestTop"
    )
  )
}



