package rvtng.mat

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import chisel3._
import chisel3.util._
import rvtng.top.{TensorInst, TensorResp}


class MatCSRStorage extends Bundle {
  val M = UInt(20.W)
  val N = UInt(20.W)
  val K = UInt(20.W)
  val AStride  = UInt(20.W)
  val BStride  = UInt(20.W)
  val CStride  = UInt(20.W)
  val ASwizzleMBS  = UInt(24.W)
  val BSwizzleMBS  = UInt(24.W)
  val CSwizzleMBS  = UInt(24.W)
  val PersistentMode = UInt(2.W)
  val DataType = UInt(8.W)
}

class MatCSRGroupFetchIF(params: MatParams) extends Bundle {
  val addr = Input(UInt(log2Ceil(params.tcsrBanks).W))
  val ctrl = Output(new MatCSRStorage)
}

/*
* 需求：
*    Mat 模块的 TCSR。向 MatCtrlTaskGen 提供控制信号，同时接收指令直接控制。
*
* 端口：
*    张量指令请求接口：流式接口，用于接收 TCSR 读 / 写请求
*    张量指令返回接口：流式接口，用于返回 TCSR 请求结果（如果有）
*    组配置端口：1R，读取一组张量配置信息，供指令发射逻辑逻辑使用。
* */
class MatControlStatusRegisters(params: MatParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))
  private val instRespRaw = Wire(DecoupledIO(new TensorResp()))
  instResp <> Queue(instRespRaw, 2, false, false)
  val sel = IO(new MatCSRGroupFetchIF(params))

  private val instDecode = new Bundle {
    val re      = instReq.valid & instReq.bits.instWord(14)
    val we      = instReq.valid & !instReq.bits.instWord(14)
    val rd      = instReq.bits.instWord(11,  7)
    val addr    = instReq.bits.instWord(24, 15)
    val bank    = addr(9, 5)
    val wData   = instReq.bits.rdData
    val subAddr = addr(4, 0)
  }
  instReq.ready := instRespRaw.ready | !instDecode.re

  instRespRaw.bits.rd  := instDecode.rd
  instRespRaw.bits.data := DontCare

  instRespRaw.valid    := instReq.valid & instDecode.re

  private val cksumArray = VecInit.tabulate(params.tcsrBanks)(i => {
    val wSum = RegInit(0.U(20.W))
    when(instDecode.we && i.U === instDecode.bank) {
      wSum := wSum + 1.U
    }
    wSum
  })
  when(instDecode.subAddr === 31.U) {
    instRespRaw.bits.data := cksumArray(instDecode.bank(log2Ceil(params.tcsrBanks) - 1, 0))
  }
  private val TCSRPrototype = new MatCSRStorage
  TCSRPrototype.elements.toSeq.reverse.zipWithIndex.foreach {
    case ((name, fieldGen), idx) =>
      val mem = Module(new RF1R1RWAsyncRamGen(log2Ceil(params.tcsrBanks), 0, fieldGen))
      mem.raddr := sel.addr
      sel.ctrl.elements(name) := mem.rdata
      mem.waddr := instDecode.bank
      mem.we    := instDecode.we & instDecode.subAddr === idx.U
      mem.wdata := instDecode.wData
      when(instDecode.subAddr === idx.U) {
        instRespRaw.bits.data := mem.rwdata
      }
  }
}
