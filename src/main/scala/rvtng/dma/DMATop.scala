package rvtng.dma

import chisel3._
import chisel3.util._
import rvtng.bus.TLUH
import rvtng.top.{ScoreboardComp, TensorInst, TensorResp}
import rvtng.uram.{AdvURAMReq, AdvURAMResp, AdvURAMWResp}
/*
* 需求：
*    本模块作为 DMA 子系统的顶层，例化 ReorderTransposeBuffer、Ctrl、TLReader、TLWriter、URAMReader、URAMWriter 等单元并完成连线。
* 总线部分无性能开销的同时兼容 Tilelink / AXI 外部接口，使用两种总线标准的子集。
*
* 端口：
*    TL-A/D 接口：流式接口，用于发出 Tilelink 读 / 写请求
*    URAM R/W 接口：流式接口，用于发出 URAM 读 / 写请求
*
*/
case class DMAParams(
                      addressBits: Int = 48,
                      dataBits: Int = 512,
                      sourceBits: Int = 10,
                      sinkBits: Int = 4,
                      tcsrBanks: Int = 8,
                      transposeLog2CNSize: Int = 3,
                      byteType: UInt = UInt(8.W),
                      uramAddrBits: Int = 20,
                      uramPortPerChannel: Int = 4,
                      dmaIQSize: Int = 64) {
  val log2MaxOutStanding: Int = sourceBits - 1
  val log2N: Int = log2Ceil(dataBits / byteType.getWidth)
  val genD: UInt = byteType
  val N  = 1 << log2N
  val sizeBits = log2Ceil(log2Ceil(dataBits / 8))
}

class DMATop(params: DMAParams) extends Module {
  // Note: Two Independent Tilelink Bus for Read / Write
  // tlWr can be mapped to AXI4 - AW+W+B, with no Burst Transaction, no ID reuse.
  val tlWr = TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = log2Ceil(params.dataBits / 8))
  // tlRd can be mapped to AXI4 - AR+R, with no Burst Transaction, no ID reuse.
  val tlRd = TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = log2Ceil(params.dataBits / 8))

  // 指令接口
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  private val uramPort = params.dataBits / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // URAM 读口
  val uramRReq  = IO(Vec(params.dataBits / 64, Decoupled(
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
  val issueQueue = Module(new DMAIssueQueue(params))
  val csr = Module(new DMAControlStatusRegisters(params))
  val taskGen = Module(new DMATaskGen(params))
  val tlReader = Module(new DMATLReader(params))
  val uramReader = Module(new DMAURAMReader(params))
  val rotb = Module(new DMAReorderTransposeBuffer(params))
  val pubWriter = Module(new DMAPublicWriter(params))
  val tlWriter = Module(new DMATLWriter(params))
  val uramWriter = Module(new DMAURAMWriter(params))

  // 连线
  // IssueQueue 连线
  issueQueue.instReq <> instReq
  issueQueue.instResp <> instResp
  issueQueue.sbComp <> sbComp
  issueQueue.csrReq <> csr.instReq
  issueQueue.csrResp <> csr.instResp
  issueQueue.taskSpiltReq <> taskGen.instReq

  // TaskGen 连线
  taskGen.groupCtrlIf <> csr.groupCtrlIf
  taskGen.groupReq <> rotb.groupReq
  taskGen.transactionAlloc <> rotb.transactionAlloc
  tlReader.readReq.bits := taskGen.readReq.bits
  tlReader.readReq.valid := taskGen.readReq.valid & taskGen.readReq.bits.isDRAM
  uramReader.readReq.bits := taskGen.readReq.bits
  uramReader.readReq.valid := taskGen.readReq.valid & !taskGen.readReq.bits.isDRAM
  taskGen.readReq.ready := (uramReader.readReq.ready & !taskGen.readReq.bits.isDRAM) |
                           (tlReader.readReq.ready   &  taskGen.readReq.bits.isDRAM)

  // ROTB 连线
  

  issueQueue.instFinish := pubWriter.instFinish

}
