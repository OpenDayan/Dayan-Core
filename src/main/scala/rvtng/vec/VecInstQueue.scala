package rvtng.vec

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.circt.stage.ChiselStage
import rvtng.top.TensorInst
import rvtng.utils._

class VecInst(params: VecParams) extends VecDecoded(params) {
  val rs1 = UInt(64.W)
  val rs2 = UInt(64.W)
  val rd  = UInt(64.W)
}

class VecInstWSchedule(params: VecParams) extends VecInst(params) {
  val waitCycle = UInt(params.log2MaxLatency.W)
  val waitVarLat = Bool()
  val realVSrc1 = UInt(5.W)
  val realVSrc2 = UInt(5.W)
  val realVDst  = UInt(5.W)
}

class VecInstFetchResp(params: VecParams) extends Bundle {
  val inst = new VecInstWSchedule(params)
  val valid = Bool()
  val pc = UInt(params.log2MaxInstCount.W)
}

/*
* 需求：
*    本模块为向量单元提供供指机能，接收来自标量处理器核心发射的原始 32 位指令，解码为实际内部指令。
* 本模块输入，整体上实现为一个 FIFO。输出为一个可寻址读口，配合一个弹出接口用于弹出若干条（不局限于一条）指令。
* 本模块的解码分为静态部分（仅根据当前指令字即可完成解码）及动态部分（需要依赖上下文执行情况完成解码）。
* 静态部分涉及指令分派、指令执行 Opcode 。动态部分涉及指令依赖管理（解除固定延迟指令、动态延迟指令的 RAW 依赖）。
*
* 接口：
*    instReq: 流式接口，输入指令
*    instDeq: 输入接口，弹出几条指令
*    instPtr: 输入接口，当前访问的指令指针
*    instOut: 输出接口，上周期请求访问的指令数据
*
* 实现:
*    基本实现为一个 FIFO，内部有读写指针，及空闲表项计数。读出时，访问地址为读指针 + 指令指针（偏移）。
* 输入指令部分，实现一个基本 2R1W Scoreboard，记录所有指令发射周期，用于执行依赖分析。
*
*/
class VecInstQueue(params: VecParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instDeq = IO(Input(UInt(params.log2MaxInstCount.W))) // 至多弹出 maxInstCount - 1 条指令。
  val instPtr = IO(Input(UInt(params.log2MaxInstCount.W)))
//  val instValid = IO(Output(Bool()))
  val instOut = IO(Output(new VecInstFetchResp(params)))
  private val instDecoded = Wire(DecoupledIO(new VecInst(params)))
  private val decoder = Module(new VecDecode(params))
  decoder.inst := instReq.bits.instWord
//  instDecoded.bits.elements.foreach({
//    case (name, field) =>
//      if (decoder.decode.elements.contains(name)) {
//        field := decoder.decode.elements(name)
//      }
//      if(instReq.bits.elements.contains(name)) {
//        field := instReq.bits.elements(name)
//      }
//  })
  PartialAssign(instDecoded.bits, decoder.decode)
  PartialAssign(instDecoded.bits, instReq.bits)

  private val instMem = SyncReadMem(params.maxInstCount, new VecInstWSchedule(params))
  private val instMemWPtr = RegInit(0.U(params.log2MaxInstCount.W))
  private val instMemRPtr = RegInit(0.U(params.log2MaxInstCount.W))
  private val instMemCount = RegInit(0.U((params.log2MaxInstCount + 1).W))
  instMemRPtr := instMemRPtr + instDeq
  instOut.inst := instMem.read(instMemRPtr + instPtr)
  instOut.pc   := RegNext(instPtr)
  instOut.valid := RegNext(instPtr < instMemCount)
//  instValid := RegNext(instPtr < instMemCount)

  // TODO: 确定固定延迟值
  // 固定延迟，所有指令共享，3 周期取操作数，1 周期写回，调度选择取指额外延迟 3 周期，共计 3 + 1 - 3 = 1 T
  private val fixedLatency = 1
  private val maxLatency = fixedLatency + params.maxLatency
  private val log2MaxLatency = log2Up(maxLatency)

  // 最大时间戳差， maxLatency * maxInstCount
  // 时间戳位宽，log2Ceil(maxLatency * maxInstCount) + 1
  private val tsBits = log2MaxLatency + params.log2MaxInstCount + 1 // 5 + 9 == 15

  // 输入处理流水
  private val instDecodedQ = Queue(instDecoded, 2, false, false)

  // 当前的 Timestamp
  // TODO: 删除复位
  private val tsCurrent = RegInit(0.U(tsBits.W))
  private val tsCurrentIssued = Wire(UInt(tsBits.W))
  private val tsCurrentCommit = tsCurrentIssued + fixedLatency.U + instDecodedQ.bits.optionalLatency
  private val depHead = RegInit(VecInit.tabulate(2)(i => {0.U(4.W)}))

  private val realVDst = Cat(instDecodedQ.bits.vdst, depHead(instDecodedQ.bits.vdst))
  private val realVSrc1 = Cat(instDecodedQ.bits.vsrc1(4),
    depHead(instDecodedQ.bits.vsrc1(4)) - instDecodedQ.bits.vsrc1(3, 0))
  private val realVSrc2 = Cat(instDecodedQ.bits.vsrc2(4),
    depHead(instDecodedQ.bits.vsrc2(4)) - instDecodedQ.bits.vsrc2(3, 0))

  // 固定 Latency 指令 Scoreboard
  // 2R1W，指令发射后记录进 Scoreboard
  private val latScoreboard = Mem(32, UInt(tsBits.W))
  when(instDecodedQ.fire & instDecodedQ.bits.vdstv) {
    latScoreboard.write(realVDst, tsCurrentCommit)
    depHead(instDecodedQ.bits.vdst) := depHead(instDecodedQ.bits.vdst) + 1.U
  }

  private val vsrc1Ts = latScoreboard.read(realVSrc1)
  private val vsrc2Ts = latScoreboard.read(realVSrc2)
  private val vsrc1Diff = vsrc1Ts - tsCurrent
  private val vsrc2Diff = vsrc2Ts - tsCurrent
  private val vsrc21Diff = vsrc2Ts - vsrc1Ts
  private val vsrc1WaitLonger = vsrc21Diff(tsBits - 1)
  private val vsrc1Rdy = vsrc1Diff(tsBits - 1)
  private val vsrc2Rdy = vsrc2Diff(tsBits - 1)
  private val vsrc1Wait = instDecodedQ.bits.vsrc1v & !vsrc1Rdy
  private val vsrc2Wait = instDecodedQ.bits.vsrc2v & !vsrc2Rdy
  tsCurrentIssued := Mux(vsrc1Wait | vsrc2Wait,
    Mux(vsrc1Wait & (vsrc1WaitLonger | !instDecodedQ.bits.vsrc2v), vsrc1Ts, vsrc2Ts),
    tsCurrent)
  private val tsWait = Mux(vsrc1Wait | vsrc2Wait,
    Mux(vsrc1Wait & (vsrc1WaitLonger | !instDecodedQ.bits.vsrc2v), vsrc1Diff(log2MaxLatency - 1, 0), vsrc2Diff(log2MaxLatency - 1, 0)),
    0.U(log2MaxLatency.W))

  // 更新当前 Timestamp
  when(instDecodedQ.fire) {
    tsCurrent := tsCurrentIssued
  }

  // 可变 Latency 指令的 Scoreboard Entry 状态
  private val varLatValid = RegInit(0.B)
  private val varLatEntry = RegInit(0.U(5.W))
  private val varLatMatch = (realVSrc1 === varLatEntry & instDecodedQ.bits.vsrc1v) |
                    (realVSrc2 === varLatEntry & instDecodedQ.bits.vsrc2v)
  private val varLatOverflow = varLatEntry(3, 0) === 15.U
  private val varLatOverwrite = instDecodedQ.bits.optionalLatency === 0.U
  when(instDecodedQ.fire & instDecodedQ.bits.vdst.asBool) {
    when(varLatOverwrite) {
      // Only Track Load.
      varLatValid := 1.B
      varLatEntry := Cat(instDecodedQ.bits.vdst, 0.U(4.W))
    }.otherwise {
      when(varLatValid & instDecodedQ.bits.vdst === varLatEntry(4)) {
        when(varLatOverflow | varLatMatch) {
          varLatValid := 0.B
          varLatEntry := Cat(varLatEntry(4), 0.U(4.W))
        }.otherwise {
          varLatEntry := Cat(varLatEntry(4), varLatEntry(3, 0) + 1.U)
        }
      }
    }
  }

  // 生成输出指令
  varLatMatch := (realVSrc1 === varLatEntry & instDecodedQ.bits.vsrc1v) |
                 (realVSrc2 === varLatEntry & instDecodedQ.bits.vsrc2v)
  private val instWSchedule = Wire(new VecInstWSchedule(params))
  PartialAssign(instWSchedule, instDecodedQ.bits)
  instWSchedule.waitCycle := tsWait
  instWSchedule.waitVarLat := varLatValid & (varLatOverflow | varLatMatch | varLatOverwrite)
  instWSchedule.realVDst := realVDst
  instWSchedule.realVSrc1 := realVSrc1
  instWSchedule.realVSrc2 := realVSrc2

  // 队列容量更新
  instDecodedQ.ready := instMemCount =/= params.maxInstCount.U
  when(instDecodedQ.fire) {
    instMem.write(instMemWPtr, instWSchedule)
    instMemWPtr := instMemWPtr + 1.U
  }
  instMemCount := instMemCount + instDecodedQ.fire.asUInt - instDeq
}
