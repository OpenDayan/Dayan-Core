package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import rvtng.top.{ScoreboardComp, TensorInst, TensorResp}
import rvtng.uram.{AdvURAMReq, AdvURAMResp, AdvURAMWResp}

case class NMatParams(
  // 地址位宽
  val addrWidth: Int = 20,
  val MNKWidth: Int = 16,
  // 描述 DPArray 形状
  // 存驻比
  sM: Int = 2, sN: Int = 2, sK: Int = 16,
  // 运算器尺寸
  cM: Int = 16, cN: Int = 16, cK: Int = 32,
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
class NMatTop(params: NMatParams) extends Module {
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
    val instReq = IO(Flipped(Decoupled(new TensorInst)))
    val instResp = IO(Decoupled(new TensorResp))
    val sbComp = IO(Decoupled(new ScoreboardComp()))
    val finish = IO(Output(Bool()))

    // URAM Interface
    val uramRReq0 = IO(Vec(readPorts, Decoupled(new AdvURAMReq(params.addrWidth, params.idWidth, 64))))
    val uramRReq1 = IO(Vec(readPorts, Decoupled(new AdvURAMReq(params.addrWidth, params.idWidth, 64))))
    val uramWReq = IO(Vec(writePorts, Decoupled(new AdvURAMReq(params.addrWidth, params.idWidth, 64))))
    val uramWData = IO(Output(Vec(writePorts, UInt(64.W))))
    val uramRResp0 = IO(Flipped(Vec(readChannel, Decoupled(Vec(portsPerChannel,
      new AdvURAMResp(params.addrWidth, params.idWidth, 64))))))
    val uramRResp1 = IO(Flipped(Vec(readChannel, Decoupled(Vec(portsPerChannel,
      new AdvURAMResp(params.addrWidth, params.idWidth, 64))))))
    val uramWResp = IO(Flipped(Vec(writeChannel, Decoupled(Vec(portsPerChannel,
      new AdvURAMWResp(params.addrWidth, params.idWidth, 64))))))
  })

  // MAT CSR
  // Mat TCSR
  private val csr = Module(new MatControlStatusRegisters(params)).io

  // Mat Scoreboard
  private val sb = Module(new MatScoreboard(params)).io

  // Mat Task Generator
  private val tgen = Module(new MatTaskGen(params)).io

  // Mat Exec
  private val exe = Module(new MatExec(params)).io

  // Mat Post-Process
  private val pp = Module(new MatPostProcess(params)).io

  // URAM Switch-0 (A/C Read)
  private val us0 = Module(new MatMemLoadTaskIssue(params, 2, params.log2CountATile,
    math.max(log2ATransactionCount, log2CTransactionCount))).io

  // URAM Switch-1 (B Read)
  private val us1 = Module(new MatMemLoadTaskIssue(params, 1, params.log2CountBTile, log2BTransactionCount)).io

  // URAM Switch-2 (C Write)
  private val us2 = Module(new MatMemStoreTaskIssue(params)).io

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
  tgen.cmdIn.bits
  private val curIsBF16 = csr.sel.ctrl.DataType === 0.U
  tgen.cmdIn.valid := io.instReq.valid & decodeTaskSpilt
  tgen.cmdIn.bits.bf16 := curIsBF16
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
}


