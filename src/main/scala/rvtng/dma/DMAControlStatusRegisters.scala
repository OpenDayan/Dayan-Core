package rvtng.dma

import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1RWAsyncRamGen
import chisel3._
import chisel3.util._
import rvtng.top.{TensorInst, TensorResp}

class DMACSRStorage extends Bundle {
  val Block = UInt(8.W) // < 512 / 2
  val Dim0  = UInt(24.W)
  val Dim1  = UInt(24.W)
  val Dim2  = UInt(24.W)

  val InBlockStride  = UInt(28.W)
  val InDim0Stride   = UInt(28.W)
  val InDim1Stride   = UInt(28.W)
  val InDim2Stride   = UInt(28.W)
  val OutBlockStride = UInt(28.W)
  val OutDim0Stride  = UInt(28.W)
  val OutDim1Stride  = UInt(28.W)
  val OutDim2Stride  = UInt(28.W)

  val BlockTranspose    = UInt(8.W)
  val BlockPadding      = UInt(8.W)
  val RequestPadding    = UInt(8.W)
  val PaddingValue      = UInt(64.W)
  val UramWriteSwizzleS = UInt(8.W)
  val UramWriteSwizzleB = UInt(8.W)
  val UramWriteSwizzleM = UInt(8.W)
  val UramReadSwizzleS  = UInt(8.W)
  val UramReadSwizzleB  = UInt(8.W)
  val UramReadSwizzleM  = UInt(8.W)
}

class DMACSR extends DMACSRStorage {
  val CheckSum = UInt(20.W)
}


/*
* 需求：
*    DMA 模块的 TCSR。向 IssueQueue / TaskSpilt 提供控制信号，同时接收指令直接控制。
*
* 端口：
*    张量指令请求接口：流式接口，用于接收 TCSR 读 / 写请求
*    张量指令返回接口：流式接口，用于返回 TCSR 请求结果（如果有）
*    组配置端口：1R，读取一组张量配置信息，供指令发射逻辑逻辑使用。
* */
class DMAControlStatusRegisters(params: DMAParams) extends Module {
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))
  private val instRespRaw = Wire(DecoupledIO(new TensorResp()))
  instResp <> Queue(instRespRaw, 2, false, false)
  val groupCtrlIf = IO(new Bundle {
    val addr = Input(UInt(log2Ceil(params.tcsrBanks).W))
    val ctrl = Output(new DMACSRStorage)
  })

  private val instDecode = new Bundle {
    val re      = instReq.valid & instReq.bits.instWord(14)
    val we      = instReq.valid & !instReq.bits.instWord(14)
    val rd      = instReq.bits.instWord(11,  7)
    val addr    = instReq.bits.instWord(24, 15)
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
  when(instDecode.subAddr === 31.U) {
    instRespRaw.bits.data := cksumArray(instDecode.bank(log2Ceil(params.tcsrBanks) - 1, 0))
  }
  val TCSRPrototype = new DMACSRStorage
  TCSRPrototype.elements.toSeq.reverse.zipWithIndex.foreach {
    case ((name, fieldGen), idx) =>
      val mem = Module(new RF1R1RWAsyncRamGen(log2Ceil(params.tcsrBanks), 0, fieldGen))
      mem.raddr := groupCtrlIf.addr
      groupCtrlIf.ctrl.elements(name) := mem.rdata
      mem.waddr := instDecode.bank
      mem.we    := instDecode.we & instDecode.subAddr === idx.U
      mem.wdata := instDecode.wData
      when(instDecode.subAddr === idx.U) {
        instRespRaw.bits.data := mem.rwdata
      }
//      println(name + ": " + idx.toString)
  }
}

object DMAControlStatusRegisters extends App {
  ChiselStage.emitSystemVerilog(
    new DMAControlStatusRegisters(DMAParams(
      addressBits = 48, dataBits = 64, sourceBits = 10, sinkBits = 4, tcsrBanks = 8
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DMAControlStatusRegisters"
    )
  )
}

