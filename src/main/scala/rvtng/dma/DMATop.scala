package rvtng.dma

import chisel3._
import chisel3.util._
import rvtng.bus._
import rvtng.top.{ScoreboardComp, TensorInst, TensorResp}
import rvtng.uram.{Adv2R1WURAM, AdvURAMReq, AdvURAMResp, AdvURAMWResp}
import _root_.circt.stage.ChiselStage

case class DMAParams(
                      addressBits: Int = 48,
                      dataBits: Int = 512,
                      sourceBits: Int = 9,
                      sinkBits: Int = 4,
                      tcsrBanks: Int = 8,
                      transposeLog2CNSize: Int = 3,
                      byteType: UInt = UInt(8.W),
                      uramAddrBits: Int = 20,
                      uramPortPerChannel: Int = 4,
                      uramPortAlign: Int = 32,
                      dmaIQSize: Int = 32) {
  val log2MaxOutStanding: Int = sourceBits - 1
  val log2N: Int = log2Ceil(dataBits / byteType.getWidth)
  val genD: UInt = byteType
  val N  = 1 << log2N
  val sizeBits = log2Ceil(log2Ceil(dataBits / 8))
}


/*
* 需求：
*    本模块作为 DMA 子系统的顶层，例化 ReorderTransposeBuffer、Ctrl、TLReader、TLWriter、URAMReader、URAMWriter 等单元并完成连线。
* 总线部分无性能开销的兼容 Tilelink / AXI 外部接口，使用两种总线标准的子集。
*
* 端口：
*    TL-A/D 接口：流式接口，用于发出 Tilelink 读 / 写请求
*    URAM R/W 接口：流式接口，用于发出 URAM 读 / 写请求
*    instReq：流式接口，输入指令
*    instResp：流式接口，指令执行结果返回
*    sbComp：流式接口，Scoreboard 完成机制
*
*/
class DMATop(params: DMAParams, useAxi: Boolean) extends Module {
  // 指令接口
  val instReq = IO(Flipped(Decoupled(new TensorInst())))
  val instResp = IO(Decoupled(new TensorResp()))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  private val uramPort = params.dataBits / 64
  private val uramPortAlign = params.uramPortAlign
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel
  private val uramChannelAlign = uramPortAlign / uramPortPerChannel

  // URAM 读口
  val uramRReq  = IO(Vec(uramPortAlign, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramRResp = IO(Flipped(Vec(uramChannelAlign, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

  val uramRReqRaw  = Wire(Vec(uramPort, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramRRespRaw = Wire(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMResp(params.uramAddrBits,  params.sourceBits - 1, 64)))))

  // URAM 写口
  val uramWReq = IO(Vec(uramPortAlign, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramWResp = IO(Flipped(Vec(uramChannelAlign, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMWResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))
  val uramWData = IO(Output(Vec(uramPortAlign, UInt(64.W))))

  val uramWReqRaw = Wire(Vec(uramPort, DecoupledIO(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramWRespRaw = Wire(Vec(uramChannel, DecoupledIO(Vec(uramPortPerChannel,
    new AdvURAMWResp(params.uramAddrBits,  params.sourceBits - 1, 64)))))
  val uramWDataRaw = Wire(Vec(uramPort, UInt(64.W)))

  // 连线补全 uram 接口
  for(i <- 0 until uramPortAlign) {
    if(i < uramPort) {
      uramRReq(i) <> uramRReqRaw(i)
      uramWReq(i) <> uramWReqRaw(i)
      uramWData(i) := uramWDataRaw(i)
    } else {
      uramRReq(i).valid := 0.B
      uramRReq(i).bits := DontCare
      uramWReq(i).valid := 0.B
      uramWReq(i).bits := DontCare
      uramWData(i) := DontCare
    }
  }
  for(c <- 0 until uramChannelAlign) {
    if(c < uramChannel) {
      uramRResp(c) <> uramRRespRaw(c)
      uramWResp(c) <> uramWRespRaw(c)
    } else {
      uramRResp(c).ready := 1.B
      uramWResp(c).ready := 1.B
    }
  }

  // 子模块
  private val issueQueue = Module(new DMAIssueQueue(params))
  private val csr = Module(new DMAControlStatusRegisters(params))
  private val taskGen = Module(new DMATaskGen(params))
  private val tlReader = Module(new DMATLReader(params))
  private val uramReader = Module(new DMAURAMReader(params))
  private val rotb = Module(new DMAReorderTransposeBuffer(params))
  private val pubWriter = Module(new DMAPublicWriter(params))
  private val tlWriter = Module(new DMATLWriter(params))
  private val uramWriter = Module(new DMAURAMWriter(params))

  // 连线
  // IssueQueue 连线
  issueQueue.instReq <> instReq
  issueQueue.instResp <> instResp
  issueQueue.sbComp <> sbComp
  issueQueue.csrReq <> csr.instReq
  issueQueue.csrResp <> csr.instResp
  issueQueue.taskSpiltReq <> taskGen.instReq
  issueQueue.instFinish := pubWriter.instFinish

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

  // Reader Arbiter
  private val reader = Module(new RRArbiter(uramReader.readResp.bits.cloneType, 2)).io
  reader.in(0) <> tlReader.readResp
  reader.in(1) <> uramReader.readResp

  // ROTB 连线
  rotb.transactionComp <> reader.out
  rotb.outStream <> pubWriter.inReqStream

  // Writer Arbiter
  private val writer = Module(new RRArbiter(uramWriter.writeResp.bits.cloneType, 2)).io
  writer.in(0) <> tlWriter.writeResp
  writer.in(1) <> uramWriter.writeResp

  // pubWriter 连线
  pubWriter.outCompStream <> writer.out
  tlWriter.writeReq.bits := pubWriter.outReqStream.bits
  tlWriter.writeReq.valid := pubWriter.outReqStream.valid & pubWriter.outReqStream.bits.isDRAM
  uramWriter.writeReq.bits := pubWriter.outReqStream.bits
  uramWriter.writeReq.valid := pubWriter.outReqStream.valid & !pubWriter.outReqStream.bits.isDRAM
  pubWriter.outReqStream.ready := (tlWriter.writeReq.ready & pubWriter.outReqStream.bits.isDRAM) |
                                  (uramWriter.writeReq.ready & !pubWriter.outReqStream.bits.isDRAM)

  uramReader.uramReq <> uramRReqRaw
  uramReader.uramResp <> uramRRespRaw
  uramWriter.uramReq <> uramWReqRaw
  uramWriter.uramWData <> uramWDataRaw
  uramWriter.uramResp <> uramWRespRaw
  // Note: Two Independent Tilelink Bus for Read / Write
  // tlWr can be mapped to AXI4 - AW+W+B, with no Burst Transaction, no ID reuse.
  // tlRd can be mapped to AXI4 - AR+R, with no Burst Transaction, no ID reuse.
  val axi4Parms = new AXI4BundleParameters(
    addressBits = params.addressBits, dataBits = params.dataBits, idBits = params.sourceBits
  )
  val tlParms = new TLBundleParameters(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = log2Ceil(params.dataBits / 8), hasBCE = false)
  val axi4 = if(useAxi) IO(new AXI4Bundle(axi4Parms)) else WireDefault(0.U.asTypeOf(new AXI4Bundle(axi4Parms)))
  val tlWr = if(!useAxi) IO(new TLBundle(tlParms)) else WireDefault(0.U.asTypeOf(new TLBundle(tlParms)))
  val tlRd = if(!useAxi) IO(new TLBundle(tlParms)) else WireDefault(0.U.asTypeOf(new TLBundle(tlParms)))

  if(useAxi) {
    val tlToAxi4 = Module(new TilelinkUHToAXI(tlParms))
    tlToAxi4.axi4 <> axi4
    tlReader.tl <> tlToAxi4.tlRd
    tlWriter.tl <> tlToAxi4.tlWr
  }else {
    // Reader 连线
    tlReader.tl <> tlRd
    // Writer 连线
    tlWriter.tl <> tlWr
  }
}

class DMATestTop(params: DMAParams) extends Module {
  private val uramPort = params.dataBits / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // URAM Instance
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 1, R1Port = 1, W0Port = 1, GroupCount = params.uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = params.sourceBits, dataWidth = 64
  ))

  val axi4Parms = new AXI4BundleParameters(
    addressBits = params.addressBits, dataBits = params.dataBits, idBits = params.sourceBits
  )
  // DPI Instance
  val axi4 = Module(new DPIAXI4(axi4Parms, 0)).axi

  // DMA Instance
  private val dma = Module(new DMATop(params, true))

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  dma.instReq <> instReq
  dma.instResp <> instResp
  dma.sbComp <> sbComp
  dma.uramRReq <> uram.r0req_i(0)
  dma.uramRResp <> uram.r0resp_o(0)
  dma.uramWReq <> uram.wreq_i(0)
  dma.uramWResp <> uram.wresp_o(0)
  dma.axi4 <> axi4
  uram.wdata_i(0) := dma.uramWData
  uram.r1req_i(0).map(u => {
    u.bits := DontCare
    u.valid := 0.B
  })
  uram.r1resp_o(0).map(u => {
    u.ready := 1.B
  })

}



object DMATestTop extends App {
  ChiselStage.emitSystemVerilog(
    new DMATestTop(DMAParams(
      addressBits = 48, dataBits = 512, sourceBits = 9,
      sinkBits = 4, tcsrBanks = 8, transposeLog2CNSize = 3,
      byteType = UInt(8.W), uramAddrBits = 20, uramPortPerChannel = 4, uramPortAlign = 8, dmaIQSize = 32
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DMATestTop"
    )
  )
}
