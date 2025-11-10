package rvtng.dma

import rvtng.utils.{RF1R1WRamGen, ReorderScoreboard, Transposer}
import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

// ReOrder and Transpose Buffer
// Reorder TL/AXI Response Message to FIFO Order, and do optional Transpose.
/*
*  需求：
*      本模块以访存组（2^logN * 64B）为单位完成内存事务的重排序、Padding 及转置。每访存组，包含 2^logN 个内存事务（由请求及完成组成）。
*  注意，在 RVTNG 的设计中，所有内存事务均为 Unburst 类型，充分提供访存的灵活性。当然，连续的访存仍然有利于 DRAM 缓存命中，提高访问吞吐。
*
*  接口：
*      访存组请求接口(groupReq)：流式输入接口，输入访存组元信息，之后内存事务 ID 分配接口开始为该访存组输出分配好的 ID。
*      内存事务 ID 分配接口(transactionAlloc)：流式输出接口，用于分配内存事务 ID，Payload 为内存事务 ID。
*      内存事务完成接口(transactionComp)：流式输入接口，无反压（ready == 1）。用于处理内存事务完成事件，Payload 为内存事务 ID 及数据。
*      输出流式接口(outStream)：流式输出转置完成后的数据，有反压。
*
*  实现：
*      访问组元信息包括输出元信息（可选数据类型）、Padding 信息（访存组 Padding 及每请求 Padding）、PaddingValue（用于 Padding 的值）
*  转置信息。
*      其中，转置信息、Padding 信息、访问组首事务 ID 及 PaddingValue 需要可被内存事务 ID 直接索引，每次请求到来时，均需要这些信息。
*  因此，需要专门设置一个 Transaction Info SRAM（1R1W），在内存事务 ID 分配接口 Fire 的同时，将这些信息填入表中。
*      对于其它不需要在每次 Transaction 中都使用的信息，可以放入 Group Info SRAM （1R1W），在访存组请求 Fire 的同时，将这些信息填入表中。
*  输出直达输出流式接口。
*      由于请求被乱序异步完成，因此我们需要使用 ReorderScoreboard 来记录每个访存组请求的完成情况。
*      当内存事务完成接口 Fire 时，T0 时刻先读取 Transaction Info SRAM，T1 时刻使用获取到的元信息，做 Padding -> ROR -> Write DMA
*  Buffer、更新 ReorderScoreboard。
*      当 ReorderScoreboard 队首数据有效时，T0 读出相应 DMA Buffer 数据及 Group Info SRAM，T1 对相应数据做 ROR 并送入 Transposer，
*  更新读指针， Group Info SRAM 数据写入 Output Info FIFO，注意该队列容量理论上为 N 即可（ready 级联）。
*    注意： Transposer 应支持 ready 反压，ready 信号级联回输入。
*
*  注意：
*      Tilelink Request ID(Source) 在相应 Response 到达之前不可被复用，AXI ID 可以复用，并保证相同 ID 的若干请求按照请求顺序返回。
*  本设计需要同时兼容 AXI 及 Tilelink，因此假设 ID 不可复用，每一次 64B（内存位宽）请求，均需要占用一个 ID，若干 ID 联合组成访存组。
*
*/

class DMAReadComp(params: DMAParams) extends Bundle {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val N  = 1 << log2N
  private val genD: UInt = params.byteType
  val data  = Vec(N, genD)
  val tid   = UInt(log2MaxOutStanding.W)
}

class DMAStreamDataOut(params: DMAParams) extends Bundle {
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val genD: UInt = params.byteType
  private val N  = 1 << log2N
  val data = Vec(N, genD)
  val info = new DMAWriteTaskInfo(params)
}

// N-Bytes Input, D Means One Byte.
class DMAReorderTransposeBuffer(params: DMAParams) extends Module {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  private val genD: UInt = params.byteType

  private val N  = 1 << log2N
  private val CN = 1 << log2CN
  private val log2BANK = log2N - log2CN
  private val BANK = N / CN
  private val maxOutStanding = 1 << log2MaxOutStanding

  val groupReq = IO(Flipped(Decoupled(new DMAGroupReq(params))))
  // Input Group Spilt to Block
  private val groupSpilter = Module(new DMAGroupReqSpilt(params))

  groupSpilter.groupReq <> groupReq

  private val blockReqQ = Queue(groupSpilter.blockReq, 2, false, false)

  val transactionAlloc = IO(Decoupled(UInt(log2MaxOutStanding.W)))
  val transactionComp  = IO(Flipped(Decoupled(new DMAReadComp(params))))
  transactionComp.ready := 1.B
  val outStream = IO(Decoupled(new DMAStreamDataOut(params)))

  // group Allocate Logic
  // transaction ID Alloc Logic
  private val inCurGroupHead    = RegInit(0.U(log2MaxOutStanding.W))
  private val inCurAllocateHead = RegInit(0.U(log2MaxOutStanding.W))
  private val inCurUseableEntry = RegInit(maxOutStanding.U((log2MaxOutStanding + 1).W))
  private val inCurIsFirst      = inCurGroupHead === inCurAllocateHead
  private val outFreeEntryCount = Wire(UInt(log2N.W))
  outFreeEntryCount := 0.U // Default: No Release

  // Padding FIFO
  private val padFIFO = Module(new Queue(UInt(log2MaxOutStanding.W), maxOutStanding / 2, false, false, true)).io

  // transaction Info Ram
  private val transactionInfoRam = Module(new RF1R1WRamGen(log2MaxOutStanding, new Bundle {
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
    val firstId   = UInt(log2MaxOutStanding.W)
  }))
  private val transactionIssued = transactionAlloc.fire | padFIFO.enq.fire
  transactionInfoRam.we := transactionIssued
  transactionInfoRam.waddr := transactionAlloc.bits
  transactionInfoRam.wdata.reqPad    := blockReqQ.bits.reqPad
  transactionInfoRam.wdata.padValue  := blockReqQ.bits.padValue
  transactionInfoRam.wdata.transpose := blockReqQ.bits.transpose
  transactionInfoRam.wdata.firstId   := inCurGroupHead

  // Block Info Queue
  private val blockInfoQ = Module(new Queue(new Bundle {
    val keepInfo  = new DMAWriteTaskInfo(params)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
  }, maxOutStanding, false, false, true)).io
  blockInfoQ.enq.bits.keepInfo := blockReqQ.bits.keepInfo
  blockInfoQ.enq.bits.transpose := blockReqQ.bits.transpose
  blockInfoQ.enq.valid := transactionIssued & inCurIsFirst

  // DMA Buffer
  private val dmaBuffer = (0 until BANK).map(i => {
    Module(new RF1R1WRamGen(log2MaxOutStanding, Vec(CN, genD)))
  })

  // Reorder Counter
  private val scoreboard = Module(new ReorderScoreboard(maxOutStanding, N))

  // Input Allocate Stream Controlling Logic
  private val inCurIdx = Reg(UInt(log2N.W))
  private val inCurIsPad = inCurIdx > blockReqQ.bits.groupPad
  transactionAlloc.bits := inCurAllocateHead
  transactionAlloc.valid := inCurUseableEntry =/= 0.U & blockReqQ.valid & !inCurIsPad
  inCurAllocateHead := inCurAllocateHead + transactionIssued.asUInt
  inCurUseableEntry := inCurUseableEntry - transactionIssued.asUInt + outFreeEntryCount
  padFIFO.enq.bits := inCurAllocateHead
  padFIFO.enq.valid := inCurUseableEntry =/= 0.U & blockReqQ.valid & inCurIsPad
  private val nextCurIdx = inCurIdx +& 1.U
  blockReqQ.ready := 0.B
  when(transactionIssued) {
    when(inCurIdx === blockReqQ.bits.groupSz) {
      // Last Request In Group
      inCurIdx := 0.U
      inCurGroupHead := inCurGroupHead + nextCurIdx
      blockReqQ.ready := 1.B
    }.otherwise {
      inCurIdx := nextCurIdx
    }
  }

  // Input Completion Stream Handling Logic
  private val t0CompInfo = Wire(new Bundle{
    val isPad = Bool()
    val data  = Vec(BANK, Vec(CN, genD))
    val tid   = UInt(log2MaxOutStanding.W)
  })
  private val t0Valid = transactionComp.valid | padFIFO.deq.valid
  padFIFO.deq.ready := !transactionComp.valid
  t0CompInfo.isPad := !transactionComp.valid
  t0CompInfo.data  := transactionComp.bits.data.asTypeOf(t0CompInfo.data)
  t0CompInfo.tid   := Mux(transactionComp.valid, transactionComp.bits.tid, padFIFO.deq.bits)
  private val t1CompInfo = RegNext(t0CompInfo)
  private val t1Valid    = RegNext(t0Valid)
  // Fetching Transaction Info Ram
//  val t1TransInfo = transactionInfoRam.read(t0CompInfo.tid, t0Valid)
  private val t1TransInfo = transactionInfoRam.rdata
  transactionInfoRam.raddr := t0CompInfo.tid
  // Calculate Ror of Input Data
  /*
  * ROR = t1TransIdx / (blockSz / Bank)
  * ROR = (t1TransIdx << log2BANK) >> log2BlockSz
  * */
  private val t1TransIndex = (t1CompInfo.tid - t1TransInfo.firstId)(log2N - 1, 0) // log2MaxOutStanding bits -> Log2BlockSz bits
  private val t1TransLog2BlockSz = log2N.U - t1TransInfo.transpose // 0, ..., log2N
  private val t1Ror = (Cat(t1TransIndex, 0.U(log2BANK.W)) >> t1TransLog2BlockSz).asUInt // Log2BlockSz + log2Bank - Log2BlockSz = log2Bank

  private val t2Valid = RegNext(t1Valid)
  private val t2CompInfo = RegNext(t1CompInfo)
  private val t2TransInfo = RegNext(t1TransInfo)
  private val t2Ror = RegNext(t1Ror)
  private val t2BlockSz = RegNext(((1.U((log2N + 1).W) << t1TransLog2BlockSz).asUInt - 1.U)(log2N - 1, 0))

  // Write to DMA Buffer
  private val t2PadData = VecInit.tabulate(BANK)(b => {
    VecInit.tabulate(CN)(c => {
      val needMask = ((b * CN + c).U > t2TransInfo.reqPad) | t2CompInfo.isPad
      Mux(needMask, t2TransInfo.padValue(c), t2CompInfo.data(b)(c))
    })
  })
  private val t2RorData = VecInit.tabulate(BANK)(b => {
    t2PadData((b.U - t2Ror.asUInt)(log2BANK - 1, 0))
  })
  (0 until BANK).foreach(b => {
//    dmaBuffer(b).write(t2CompInfo.tid, t2RorData(b))
    dmaBuffer(b).we := t2Valid
    dmaBuffer(b).waddr := t2CompInfo.tid
    dmaBuffer(b).wdata := t2RorData(b)
  })
  scoreboard.comp.valid := t2Valid
  scoreboard.comp.reqGroupID := t2TransInfo.firstId
  scoreboard.comp.reqGroupSize := t2BlockSz

  // Output Stream Handling Logic
  private val trInReady = Wire(Bool())
  private val t0OutCurBlockHead = RegInit(0.U(log2MaxOutStanding.W))
  scoreboard.check.reqGroupID := t0OutCurBlockHead
  scoreboard.check.fire := blockInfoQ.deq.fire
  // Note: Output->Transposer is Hard-Wired Pipeline
  // T0 Fetch BlockInfo
  private val t0OutInfo = blockInfoQ.deq.bits
  private val t0OutCurIdx = RegInit(0.U(log2N.W))
  private val t0OutCurBankIdx = RegInit(0.U(log2BANK.W))
  private val t0OutCurCIdx = RegInit(0.U(log2CN.W))
  private val t0OutCurBlockValid = scoreboard.check.reqGroupValid
  private val t0OutCurBlockSz = ((N-1).U >> blockInfoQ.deq.bits.transpose).asUInt
  private val t0Outlog2BlockSz = log2N.U - blockInfoQ.deq.bits.transpose
  private val t0OutCurLast = t0OutCurIdx === t0OutCurBlockSz
  blockInfoQ.deq.ready := 0.B
  /*
  * ROR = t0OutCurIdx / (t0OutCurBlockSz / Bank)
  * ROR = (t0OutCurIdx << log2BANK) >> t0Outlog2BlockSz
  * */
  private val t0OutRor = (Cat(t0OutCurIdx, 0.U(log2BANK.W)) >> t0Outlog2BlockSz)(log2BANK - 1, 0).asUInt
  private val t0Outlog2CNCycle = log2CN.U - Mux(t0OutInfo.transpose >= log2CN.U, log2CN.U, t0OutInfo.transpose(log2Ceil(log2CN) - 1, 0))
  private val t0OutCurIdxP1 = t0OutCurIdx + 1.U
  private val t0OutCurCIdxP1 = t0OutCurCIdx + 1.U
  when(t0OutCurBlockValid & trInReady) {
    when(t0OutCurLast) {
      t0OutCurIdx := 0.U
      t0OutCurCIdx := 0.U
      t0OutCurBankIdx := 0.U
      t0OutCurBlockHead := t0OutCurBlockHead + t0OutCurIdxP1
      outFreeEntryCount := t0OutCurIdxP1
      blockInfoQ.deq.ready := 1.B
    }.otherwise {
      t0OutCurIdx := t0OutCurIdxP1
      val cidxCarry = t0OutCurCIdx === ((1.U(log2CN.W) << t0Outlog2CNCycle).asUInt - 1.U)
      t0OutCurCIdx := Mux(cidxCarry, 0.U, t0OutCurCIdxP1)
      t0OutCurBankIdx := t0OutCurBankIdx + cidxCarry.asUInt
    }
  }
  // raddr == ((((b * CN) / (transpose)) - idx) & (BlockSz - 1)) + base
  private val t1OutDataRaw = VecInit.tabulate(BANK)(b => {
    dmaBuffer(b).raddr := Mux(!trInReady, RegNext(dmaBuffer(b).raddr),
      // TODO: Check Me
      t0OutCurBlockHead + (((Cat(b.U(log2BANK.W), 0.U(log2CN.W)) >> t0OutInfo.transpose).asUInt
        + t0OutCurCIdx - (Cat(0.U(log2CN.W), t0OutCurBankIdx) << t0Outlog2CNCycle).asUInt) & t0OutCurBlockSz)
    )
    dmaBuffer(b).rdata
  })
  private val t1OutValid = RegEnable(t0OutCurBlockValid, trInReady)
  private val t1OutRor   = RegEnable(t0OutRor, trInReady)
  private val t1OutInfo  = RegEnable(blockInfoQ.deq.bits, trInReady)
  private val t1OutData  = VecInit.tabulate(BANK)(b => {
    t1OutDataRaw((b.U + t1OutRor.asUInt)(log2BANK - 1, 0))
  })
  // TODO: Smaller fifo maybe, since max latency is CN?
  private val trInfoQ = Module(new Queue(new DMAWriteTaskInfo(params), 2 * CN, false, false)).io
  trInfoQ.enq.bits := t1OutInfo.keepInfo
  trInfoQ.enq.valid := t1OutValid & trInReady
  private val tr = Module(new Transposer(BANK, CN, genD))
  tr.in.bits.elemSize := Mux(t1OutInfo.transpose >= log2CN.U, log2CN.U, t1OutInfo.transpose(log2Ceil(log2CN) - 1, 0))
  tr.in.bits.stream := t1OutData
  tr.in.valid := t1OutValid
  trInReady := tr.in.ready
  outStream.bits.data := tr.out.bits.asTypeOf(outStream.bits.data)
  outStream.bits.info := trInfoQ.deq.bits
  outStream.valid := tr.out.valid
  tr.out.ready := outStream.ready
  trInfoQ.deq.ready := outStream.fire
}

object DMAReorderTransposeBuffer extends App {
  ChiselStage.emitSystemVerilog(
    new DMAReorderTransposeBuffer(DMAParams(
      dataBits = 2048,
      transposeLog2CNSize = 3,
      byteType = UInt(32.W)
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DMAReorderTransposeBuffer"
    )
  )
}
