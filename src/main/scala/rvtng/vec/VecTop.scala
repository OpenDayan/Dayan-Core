package rvtng.vec

import chisel3._
import chisel3.util._
import rvtng.top._
import rvtng.uram._

case class VecParams(
                    maxInstCount: Int = 512,
                    maxThreadCount: Int = 32, // 最多同时 32 个并行线程
                    maxLatency: Int = 8,
                    tcsrBanks: Int = 8,
                    laneWidth: Int = 128,
                    uramAddrBits: Int = 22,
                    sourceBits: Int = 0,
                    uramPortPerChannel: Int = 4
                    ) {
  val log2MaxInstCount = log2Ceil(maxInstCount)
  val log2MaxLatency = log2Ceil(maxLatency)
  val log2MaxThreadCount = log2Ceil(maxThreadCount)
}

/*
* 需求：
*    向量部分顶层，例化各个子系统并完成连线。
*
* 端口：
*    URAM R/W 接口：流式接口，用于发出 URAM 读 / 写请求
*    instReq：流式接口，输入指令
*    instResp：流式接口，指令执行结果返回
*    sbComp：流式接口，Scoreboard 完成机制
*
* */
class VecTop(params: VecParams) extends Module {
  // 端口
  // 指令接口
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  private val uramPort = (params.laneWidth * 32 / 2) / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // URAM 读口
  val uramRReq  = IO(Vec(uramPort, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramRResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

  // URAM 写口
  val uramWReq = IO(Vec(uramPort, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramWData = IO(Output(Vec(uramPort, UInt(64.W))))
  val uramWResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMWResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

  // 子模块
  private val instQueue = Module(new VecInstQueue(params))
  private val csr = Module(new VecControlStatusRegisters(params))
  private val sb  = Module(new VecScoreboard(params))
  private val taskGen = Module(new VecTaskGen(params))
  private val dispatch = Module(new VecTaskDispatch(params))
  private val fetchMgr = Module(new VecFetchManager(params))
  private val threads = Seq.tabulate(params.maxThreadCount)(t => {
    Module(new VecThreadCtrl(params, t))
  })
  private val scheduler = Module(new VecGlobalScheduler(params))
  private val rf = Module(new VecRegfile(params, 1, 1))

  // FU
  private val FUDispatch = Module(new VecFUDispatch(params))
  private val FUInt = Module(new VecFUInt(params))
  private val FULsu = Module(new VecFULsu(params))

  // 接线
  instQueue.instReq <> instReq
  instQueue.instPtr := fetchMgr.instPtr
  instQueue.instDeq := fetchMgr.instDeq

  // TODO: Scoreboard
  csr.instReq <> fetchMgr.csrInst
  sb.instReq <> fetchMgr.sbInst
  val respArb = Module(new RRArbiter(csr.instReq.bits.cloneType, 2)).io
  respArb.in(0) <> csr.instResp
  respArb.in(1) <> sb.instResp
  respArb.out <> instResp
  sb.sbComp <> sbComp

  taskGen.csrInfo := csr.csrInfo
  taskGen.threadTask <> dispatch.taskIn
  taskGen.instWindows := instQueue.instOut
  taskGen.blockStart := fetchMgr.blockStart
  taskGen.threadFinish := !VecInit.tabulate(params.maxThreadCount)(t => threads(t).busy).asUInt.orR

  dispatch.taskOut <> VecInit.tabulate(params.maxThreadCount)(t => threads(t).allocateReq)

  fetchMgr.fetchIn <> VecInit.tabulate(params.maxThreadCount)(t => {
    threads(t).fetchResp := instQueue.instOut
    threads(t).fetchReq
  })
  fetchMgr.instWindows := instQueue.instOut
  fetchMgr.blockEnd := taskGen.blockEnd

  scheduler.preIssue <> VecInit.tabulate(params.maxThreadCount)(t => {
    threads(t).preIssueResp := scheduler.preIssueResp
    threads(t).varCommit // TODO: Connect to LSU
    threads(t).preIssue
  })

  rf.in <> scheduler.issue
  rf.out <> FUDispatch.in

  // FU 连线
  FUInt.inst <> FUDispatch.outInt
  FULsu.inst <> FUDispatch.outLsu

  // 固定延迟 FU
  rf.nbWP(0) := FUInt.wback

  // 可变延迟 FU
  rf.bWP(0) <> FULsu.wback
}
