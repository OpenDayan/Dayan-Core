package rvtng.vec

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import chisel3._
import chisel3.util._
import rvtng.top.{TensorInst, TensorResp}

class VecCSRStorage(params: VecParams) extends Bundle {
  val Block = UInt(8.W)
  val Dim0 = UInt(16.W)
  val Dim1 = UInt(16.W)
  val Dim0VStride0 = UInt(28.W)
  val Dim1VStride0 = UInt(28.W)
  val Dim0SStride0 = UInt(28.W)
  val Dim1SStride0 = UInt(28.W)
  val Dim0VStride1 = UInt(28.W)
  val Dim1VStride1 = UInt(28.W)
  val Dim0SStride1 = UInt(28.W)
  val Dim1SStride1 = UInt(28.W)
  val Dim0VStride2 = UInt(28.W)
  val Dim1VStride2 = UInt(28.W)
  val Dim0SStride2 = UInt(28.W)
  val Dim1SStride2 = UInt(28.W)
  val Dim0VStride3 = UInt(28.W)
  val Dim1VStride3 = UInt(28.W)
  val Dim0SStride3 = UInt(28.W)
  val Dim1SStride3 = UInt(28.W)
}

/*
* 需求：
*    Vec 模块的 TCSR。向 GlobalScheduler 提供控制信号，同时接收指令直接控制。
*
* 端口：
*    张量指令请求接口：流式接口，用于接收 TCSR 读 / 写请求
*    张量指令返回接口：流式接口，用于返回 TCSR 请求结果（如果有）
*    组配置端口：1R，读取一组张量配置信息，供 GlobalScheduler 逻辑逻辑使用。
*
* */
class VecControlStatusRegisters(params: VecParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new VecInstWSchedule(params))))
  val instResp = IO(Decoupled(new TensorResp()))
  private val instRespRaw = Wire(DecoupledIO(new TensorResp()))
  instResp <> Queue(instRespRaw, 2, false, false)
  val groupCtrlIf = IO(Output(new VecCSRStorage(params)))

  val instRebuild = VecInstRebuild(instReq.bits)

  private val instDecode = new Bundle {
    val re      = instReq.valid & instRebuild(14)
    val we      = instReq.valid & !instRebuild(14)
    val rd      = instRebuild(11,  7)
    val addr    = instRebuild(24, 15)
    val bank    = addr(9, 5)
    val wData   = instReq.bits.rd
    val subAddr = addr(4, 0)
  }
  instReq.ready := instRespRaw.ready

  instRespRaw.bits.rd  := Mux(instDecode.re, instDecode.rd, 0.U)
  instRespRaw.bits.data := DontCare

  instRespRaw.valid    := instReq.valid

  private val cksumArray = VecInit.tabulate(params.tcsrBanks)(i => {
    val wSum = RegInit(0.U(20.W))
    when(instDecode.we && i.U === instDecode.bank) {
      wSum := wSum + 1.U
    }
    wSum
  })
  val ridxReg = RegInit(0.U(3.W))
  when(instDecode.subAddr === 0.U & instDecode.bank === params.tcsrBanks.U) {
    when(instDecode.we) {
      ridxReg := instDecode.wData
    }
    instRespRaw.bits.data := ridxReg
  }
  when(instDecode.subAddr === 31.U) {
    instRespRaw.bits.data := cksumArray(instDecode.bank(log2Ceil(params.tcsrBanks) - 1, 0))
  }
  val TCSRPrototype = new VecCSRStorage(params)
  TCSRPrototype.elements.toSeq.reverse.zipWithIndex.foreach {
    case ((name, fieldGen), idx) =>
      val mem = Module(new RF1R1RWAsyncRamGen(log2Ceil(params.tcsrBanks), 0, fieldGen))
      mem.raddr := ridxReg
      groupCtrlIf.elements(name) := mem.rdata
      mem.waddr := instDecode.bank
      mem.we    := instDecode.we & instDecode.subAddr === idx.U && instDecode.bank < params.tcsrBanks.U
      mem.wdata := instDecode.wData
      when(instDecode.subAddr === idx.U && instDecode.bank < params.tcsrBanks.U) {
        instRespRaw.bits.data := mem.rwdata
      }
          println(name + ": " + idx.toString)
  }
}

