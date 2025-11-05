package rvtng.vec

import chisel3._
import chisel3.util._
import rvtng.uram.{AdvURAMReq, AdvURAMResp, AdvURAMWResp}
import rvtng.utils.PartialAssign

/*
* 需求：
*    本模块实现 Lsu 的 Function Unit，输入来自 Regfile，并输出到 Regfile，同时需要唤醒相应的 Thread Controller（消除 Var Lat 指令数据 Bar）。
*
* 接口：
*    inst:  带有数据的指令流 VecInstRData, 注意检查该接口不存在 deny。
*    wback: 写回请求，有阻塞流式接口
*    varCommit: 提交接口，指令提交时通知 ThreadCtrl，消除 Barrier。
*    uramRReq: uram 读请求流式端口
*    uramWReq: uram 写请求流式端口
*    uramRResp: uram 读响应流式端口
*    uramWResp: uram 写响应流式端口（记录 on the fly 请求数目，用于同步操作）
*    busy: 提供给 global task gen 使用的忙信号，用于同步操作
* */

class VecFULsu(params: VecParams) extends Module {
  val inst = IO(Flipped(Decoupled(new VecInstRData(params))))
  private val instQ = Queue(inst, 4) // Credit Based Fifo
  val lsuCreditGain = IO(Output(Bool()))
  lsuCreditGain := instQ.fire

  // uram Read Port Handling
  private val uramPort = params.laneWidth * 4 / 8
  private val uramChannel = uramPort / params.uramPortPerChannel
  val uramRReq  = IO(Vec(uramPort, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))

  private val readIssued = Wire(Vec(uramChannel, Bool()))
  private val readIssuedAll = readIssued.asUInt.andR
  // read req handling
  for(i <- 0 until uramPort) {
    val issueHighMask = RegInit(0.B)
    val issuedMask = RegInit(0.B)
    uramRReq(i).valid := instQ.valid & !instQ.bits.inst.optype(5) & !issuedMask
    if((i % params.uramPortPerChannel) == 0) {
      readIssued(i / params.uramPortPerChannel) :=
        issuedMask | (uramRReq((i / params.uramPortPerChannel) * params.uramPortPerChannel).fire & issueHighMask)
    }
    issuedMask := (
        issuedMask | (uramRReq((i / params.uramPortPerChannel) * params.uramPortPerChannel).fire & issueHighMask)) &
      !readIssuedAll
    issueHighMask := issueHighMask ^ uramRReq((i / params.uramPortPerChannel) * params.uramPortPerChannel).fire
    // TODO 1: Add Swizzle here
    // TODO 2: Add More Address Mode here
    uramRReq(i).bits.address := instQ.bits.inst.rs2Data + Mux(instQ.bits.inst.optype(0), 0.U,
      Mux(issueHighMask, (i + uramPort).U, i.U)
    )
    uramRReq(i).bits.id := 0.U
  }

  // read resp handling
  val uramRResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(params.uramPortPerChannel,
    new AdvURAMResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))
  private val readLoVec = Reg(Vec(uramChannel,  Vec(params.uramPortPerChannel, UInt(64.W))))
  private val readHiVec = Wire(Vec(uramChannel,  Vec(params.uramPortPerChannel, UInt(64.W))))
  private val readRecv = Wire(Vec(uramChannel, Bool()))
  private val readRecvAll = readRecv.asUInt.andR
  private val readQ = Module(new Queue(Vec(params.laneWidth, UInt(32.W)), 2, false, false)).io
  for(i <- 0 until uramChannel) {
    val recvLowMask = RegInit(1.B)
    uramRResp(i).ready := (readRecvAll & readQ.enq.ready) | recvLowMask
    recvLowMask := recvLowMask ^ uramRResp(i).fire
    readRecv(i) := (!recvLowMask) & uramRResp(i).valid
    when(recvLowMask & uramRResp(i).valid) {
      readLoVec(i) := VecInit(uramRResp(i).bits.map(_.data))
    }
    readHiVec(i) := VecInit(uramRResp(i).bits.map(_.data))
  }
  readQ.enq.bits := Cat(readHiVec.asUInt, readLoVec.asUInt).asTypeOf(readQ.enq.bits)
  readQ.enq.valid := readRecvAll

  // Read Info Queue
  // TODO: Queue Size
  private val instInfoQ = Module(new Queue(UInt((5 + params.log2MaxThreadCount).W), 64, false, false)).io
  instInfoQ.enq.valid := instQ.fire & !instQ.bits.inst.optype(5) // read only
  instInfoQ.enq.bits  := Cat(instQ.bits.htId, instQ.bits.inst.realVDst)
  chisel3.assert(!(instInfoQ.enq.valid & !instInfoQ.enq.ready)) // Check No OverFlow

  val wback = IO(Decoupled(new VecWData(params)))
  wback.valid := readQ.deq.valid
  wback.bits.valid := readQ.deq.valid
  wback.bits.wData := readQ.deq.bits
  wback.bits.wAddr := instInfoQ.deq.bits
  wback.bits.htId  := instInfoQ.deq.bits(4 + params.log2MaxThreadCount, 5)

  instInfoQ.deq.ready := wback.fire
  readQ.deq.ready := wback.fire

  // uram Write Handling
  val uramWReq  = IO(Vec(uramPort, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramWData = IO(Output(Vec(uramPort, UInt(64.W))))

  private val writeIssued = Wire(Vec(uramChannel, Bool()))
  private val writeIssuedAll = readIssued.asUInt.andR
  // write req handling
  for(i <- 0 until uramPort) {
    val issueHighMask = RegInit(0.B)
    val issuedMask = RegInit(0.B)
    uramWReq(i).valid := instQ.valid & instQ.bits.inst.optype(5) & !issuedMask
    if((i % params.uramPortPerChannel) == 0) {
      writeIssued(i / params.uramPortPerChannel) :=
        issuedMask | (uramWReq((i / params.uramPortPerChannel) * params.uramPortPerChannel).fire & issueHighMask)
    }
    issuedMask := (
      issuedMask | (uramWReq((i / params.uramPortPerChannel) * params.uramPortPerChannel).fire & issueHighMask)) &
      !writeIssuedAll
    issueHighMask := issueHighMask ^ uramWReq((i / params.uramPortPerChannel) * params.uramPortPerChannel).fire
    // TODO 1: Add Swizzle here
    // TODO 2: Add More Address Mode here
    uramWReq(i).bits.address := instQ.bits.inst.rs2Data + Mux(instQ.bits.inst.optype(0), 0.U,
      Mux(issueHighMask, (i + uramPort).U, i.U)
    )
    uramWData := instQ.bits.vsrc2Data.asTypeOf(Vec(2, UInt((params.laneWidth * 32 / 2).W)))(issueHighMask).asTypeOf(uramWData)
    uramWReq(i).bits.id := 0.U
  }
  instQ.ready := readIssuedAll | writeIssuedAll

  // write channel ResponseQueue
  // TODO: Queue Size
  val uramWResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(params.uramPortPerChannel,
    new AdvURAMWResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))
  val writeBusy = VecInit.tabulate(uramChannel)(c => {
    val i = Module(new Queue(Bool(), 64, false, false)).io
    i.enq.valid := uramWReq(c * params.uramPortPerChannel).fire
    i.enq.bits := 0.B
    i.deq.ready := uramWResp(c).ready
    i.deq.valid
  }).asUInt.orR

  val busy = IO(Output(Bool()))
  busy := readQ.deq.valid | instInfoQ.deq.valid | writeBusy
}
