package dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import utils._

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
*      由于请求被乱序异步完成，因此我们需要使用 ReorderCounter 来记录每个访存组请求的完成情况。
*      当内存事务完成接口 Fire 时，T0 时刻先读取 Transaction Info SRAM，T1 时刻使用获取到的元信息，做 Padding -> ROR -> Write DMA
*  Buffer、更新 ReorderCounter。
*      当 ReorderCounter 队首数据有效时，T0 读出相应 DMA Buffer 数据及 Group Info SRAM，T1 对相应数据做 ROR 并送入 Transposer，
*  更新读指针， Group Info SRAM 数据写入 Output Info FIFO，注意该队列容量理论上为 N 即可（ready 级联）。
*    注意： Transposer 应支持 ready 反压，ready 信号级联回输入。
*
*  注意：
*      Tilelink Request ID(Source) 在相应 Response 到达之前不可被复用，AXI ID 可以复用，并保证相同 ID 的若干请求按照请求顺序返回。
*  本设计需要同时兼容 AXI 及 Tilelink，因此假设 ID 不可复用，每一次 64B（内存位宽）请求，均需要占用一个 ID，若干 ID 联合组成访存组。
*
*/

// N-Bytes Input, D Means One Byte.
class DMAReorderTransposeBuffer[D <: Data, T <: Data](log2MaxOutStanding: Int, log2N: Int, log2CN: Int,
                                                      genD: D, genT: T) extends Module {
  val N  = 1 << log2N
  val CN = 1 << log2CN
  val log2BANK = log2N - log2CN
  val BANK = N / CN
  val maxOutStanding = 1 << log2MaxOutStanding
  val groupReq = IO(Flipped(Decoupled(new Bundle {
    val keepInfo  = genT
    val groupSz   = UInt(log2MaxOutStanding.W) // GroupSize == [64B / (2^transpose)], Spilt Inside
    val groupPad  = UInt(log2MaxOutStanding.W)
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
  })))
  val blockReq = Wire(DecoupledIO(new Bundle {
    val keepInfo  = genT
    val groupSz   = UInt(log2N.W) // GroupSize == [64B / (2^transpose)], Spilt Inside
    val groupPad  = UInt(log2N.W)
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
  }))
  val transactionAlloc = IO(Decoupled(UInt(log2MaxOutStanding.W)))
  val transactionComp  = IO(new Bundle {
    val data  = Input(Vec(N, genD))
    val tid   = Input(UInt(log2MaxOutStanding.W))
    val valid = Input(Bool())
  })
  val outStream = IO(Decoupled(new Bundle {
    val data = Vec(N, genD)
    val info = genT
  }))
  // group Allocate Logic
  val blockReqQ = Queue(blockReq, 2, false, false)

  // transaction ID Alloc Logic
  val inCurGroupHead    = RegInit(0.U(log2MaxOutStanding.W))
  val inCurAllocateHead = RegInit(0.U(log2MaxOutStanding.W))
  val inCurUseableEntry = RegInit(maxOutStanding.U((log2MaxOutStanding + 1).W))
  val inCurIsFirst      = inCurGroupHead === inCurAllocateHead
  val outFreeEntryCount = Wire(UInt(log2N.W))
  outFreeEntryCount := 0.U // Default: No Release

  // transaction Info Ram
  val transactionInfoRam = Module(new RF1R1WRamGen(log2MaxOutStanding, new Bundle {
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
    val firstId   = UInt(log2MaxOutStanding.W)
  }))
  transactionInfoRam.we := transactionAlloc.fire
  transactionInfoRam.waddr := transactionAlloc.bits
  transactionInfoRam.wdata.reqPad    := blockReqQ.bits.reqPad
  transactionInfoRam.wdata.padValue  := blockReqQ.bits.padValue
  transactionInfoRam.wdata.transpose := blockReqQ.bits.transpose
  transactionInfoRam.wdata.firstId   := inCurGroupHead

  // Block Info Queue
  val blockInfoQ = Module(new Queue(new Bundle {
    val keepInfo  = genT
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
  }, maxOutStanding, false, false, true)).io
  blockInfoQ.enq.bits.keepInfo := blockReqQ.bits.keepInfo
  blockInfoQ.enq.bits.transpose := blockReqQ.bits.transpose
  blockInfoQ.enq.valid := transactionAlloc.fire & inCurIsFirst

  // DMA Buffer
  val dmaBuffer = (0 until BANK).map(i => {
    Module(new RF1R1WRamGen(log2MaxOutStanding, Vec(CN, genD)))
  })

  // Padding FIFO
  val padFIFO = Module(new Queue(UInt(log2MaxOutStanding.W), maxOutStanding / 2, false, false, true)).io

  // Reorder Counter
  val reorderCounter = Module(new DMAReorderCounter(maxOutStanding, N))

  // Input Group Spilt to Block
  val groupSpilter = Module(new DMAGroupReqSpilt(log2MaxOutStanding = log2MaxOutStanding,
    log2N = log2N, log2CN = log2CN,
    genD = genD, genT = genT))

  groupSpilter.groupReq <> groupReq
  blockReq <> groupSpilter.blockReq

  // Input Allocate Stream Controlling Logic
  val inCurIdx = Reg(UInt(log2N.W))
  val inCurIsPad = inCurIdx > blockReqQ.bits.groupPad
  transactionAlloc.bits := inCurAllocateHead
  transactionAlloc.valid := inCurUseableEntry =/= 0.U & blockReqQ.valid & !inCurIsPad
  inCurAllocateHead := inCurAllocateHead + transactionAlloc.fire.asUInt + padFIFO.enq.fire.asUInt
  inCurUseableEntry := inCurUseableEntry - transactionAlloc.fire.asUInt - padFIFO.enq.fire.asUInt + outFreeEntryCount
  padFIFO.enq.bits := inCurAllocateHead
  padFIFO.enq.valid := inCurUseableEntry =/= 0.U & blockReqQ.valid & inCurIsPad
  val nextCurIdx = inCurIdx + 1.U
  when(transactionAlloc.fire) {
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
  val t0CompInfo = Wire(new Bundle{
    val isPad = Bool()
    val data  = Vec(BANK, Vec(CN, genD))
    val tid   = UInt(log2MaxOutStanding.W)
  })
  val t0Valid = transactionComp.valid | padFIFO.deq.valid
  padFIFO.deq.ready := !transactionComp.valid
  t0CompInfo.isPad := !transactionComp.valid
  t0CompInfo.data  := transactionComp.data.asTypeOf(t0CompInfo.data)
  t0CompInfo.tid   := Mux(transactionComp.valid, transactionComp.tid, padFIFO.deq.bits)
  val t1CompInfo = RegNext(t0CompInfo)
  val t1Valid    = RegNext(t0Valid)
  // Fetching Transaction Info Ram
//  val t1TransInfo = transactionInfoRam.read(t0CompInfo.tid, t0Valid)
  val t1TransInfo = transactionInfoRam.rdata
  transactionInfoRam.raddr := t0CompInfo.tid
  // Calculate Ror of Input Data
  /*
  * ROR = t1TransIdx / (blockSz / Bank)
  * ROR = (t1TransIdx << log2BANK) >> log2BlockSz
  * */
  val t1TransIndex = (t1CompInfo.tid - t1TransInfo.firstId)(log2N - 1, 0) // log2MaxOutStanding bits -> Log2BlockSz bits
  val t1TransLog2BlockSz = log2N.U - t1TransInfo.transpose // 0, ..., log2N
  val t1Ror = (Cat(t1TransIndex, 0.U(log2BANK.W)) >> t1TransLog2BlockSz).asUInt // Log2BlockSz + log2Bank - Log2BlockSz = log2Bank

  val t2Valid = RegNext(t1Valid)
  val t2CompInfo = RegNext(t1CompInfo)
  val t2TransInfo = RegNext(t1TransInfo)
  val t2Ror = RegNext(t1Ror)
  val t2BlockSz = RegNext(((1.U((log2N + 1).W) << t1TransLog2BlockSz).asUInt - 1.U)(log2N - 1, 0))

  // Write to DMA Buffer
  val t2PadData = VecInit.tabulate(BANK)(b => {
    VecInit.tabulate(CN)(c => {
      val needMask = ((b * CN + c).U > t2TransInfo.reqPad) | t2CompInfo.isPad
      Mux(needMask, t2TransInfo.padValue(c), t2CompInfo.data(b)(c))
    })
  })
  val t2RorData = VecInit.tabulate(BANK)(b => {
    t2PadData((b.U - t2Ror.asUInt)(log2BANK - 1, 0))
  })
  (0 until BANK).foreach(b => {
//    dmaBuffer(b).write(t2CompInfo.tid, t2RorData(b))
    dmaBuffer(b).we := t2Valid
    dmaBuffer(b).waddr := t2CompInfo.tid
    dmaBuffer(b).wdata := t2RorData(b)
  })
  reorderCounter.comp.valid := t2Valid
  reorderCounter.comp.reqGroupID := t2TransInfo.firstId
  reorderCounter.comp.reqGroupSize := t2BlockSz

  // Output Stream Handling Logic
  val trInReady = Wire(Bool())
  val t0OutCurBlockHead = RegInit(0.U(log2MaxOutStanding.W))
  reorderCounter.check.reqGroupID := t0OutCurBlockHead
  reorderCounter.check.fire := blockInfoQ.deq.fire
  // Note: Output->Transposer is Hard-Wired Pipeline
  // T0 Fetch BlockInfo
  val t0OutInfo = blockInfoQ.deq.bits
  val t0OutCurIdx = RegInit(0.U(log2N.W))
  val t0OutCurBlockValid = reorderCounter.check.reqGroupValid
  val t0OutCurBlockSz = ((N-1).U >> blockInfoQ.deq.bits.transpose).asUInt
  val t0Outlog2BlockSz = log2N.U - blockInfoQ.deq.bits.transpose
  val t0OutCurLast = t0OutCurIdx === t0OutCurBlockSz
  /*
  * ROR = t0OutCurIdx / (t0OutCurBlockSz / Bank)
  * ROR = (t0OutCurIdx << log2BANK) >> t0Outlog2BlockSz
  * */
  val t0OutRor = (Cat(t0OutCurIdx, 0.U(log2BANK.W)) >> t0Outlog2BlockSz)(log2BANK - 1, 0).asUInt
  when(t0OutCurBlockValid & trInReady) {
    when(t0OutCurLast) {
      t0OutCurIdx := 0.U
    }.otherwise {
      t0OutCurIdx := t0OutCurIdx + 1.U
    }
  }
  // raddr == ((((b * CN) / (transpose)) - idx) & (BlockSz - 1)) + base
  val t1OutDataRaw = VecInit.tabulate(BANK)(b => {
    dmaBuffer(b).raddr := Mux(trInReady, RegNext(dmaBuffer(b).raddr),
      // TODO: Check Me
      ((Cat(b.U(log2BANK.W), 0.U(log2CN.W)) >> t0OutInfo.transpose).asUInt - t0OutCurIdx) & t0OutCurBlockSz
    )
    dmaBuffer(b).rdata
  })
  val t1OutValid = RegEnable(t0OutCurBlockValid, trInReady)
  val t1OutRor   = RegEnable(t0OutRor, trInReady)
  val t1OutInfo  = RegEnable(blockInfoQ.deq.bits, trInReady)
  val t1OutData  = VecInit.tabulate(BANK)(b => {
    t1OutDataRaw((b.U + t1OutRor.asUInt)(log2BANK - 1, 0))
  })
  // TODO: Smaller fifo maybe, since max latency is CN?
  val trInfoQ = Module(new Queue(genT, 2 * CN, false, false)).io
  trInfoQ.enq.bits := t1OutInfo.keepInfo
  trInfoQ.enq.valid := t1OutValid & trInReady
  val tr = Module(new Transposer(BANK, CN, genD))
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
    new DMAReorderTransposeBuffer(9, 6, 3, UInt(8.W), UInt(32.W)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DMAReorderTransposeBuffer"
    )
  )
}
