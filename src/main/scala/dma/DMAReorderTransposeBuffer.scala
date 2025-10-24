package dma

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
  val Bank = N / CN
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
  val groupReqQ = Queue(blockReq, 2, false, false)

  // transaction ID Alloc Logic
  val curGroupHead    = RegInit(0.U(log2MaxOutStanding.W))
  val curAllocateHead = RegInit(0.U(log2MaxOutStanding.W))
  val curUseableEntry = RegInit(maxOutStanding.U((log2MaxOutStanding + 1).W))
  val freeEntryCount  = Wire(UInt(log2N.W))
  val curIsFirst      = curGroupHead === curAllocateHead

  // transaction Info Ram
  val transactionInfoRam = SyncReadMem(maxOutStanding, new Bundle {
    val reqPad    = UInt(log2N.W)
    val padValue  = Vec(CN, genD)
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
    val firstId   = UInt(log2MaxOutStanding.W)
  })
  when(transactionAlloc.fire) {
    val wp = transactionInfoRam(transactionAlloc.bits)
    wp.reqPad    := groupReqQ.bits.reqPad
    wp.padValue  := groupReqQ.bits.padValue
    wp.transpose := groupReqQ.bits.transpose
    wp.firstId   := curGroupHead
  }

  // Group Info Ram
  val groupInfoRam = SyncReadMem(maxOutStanding, new Bundle {
    val keepInfo  = genT
    val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
  })
  when(transactionAlloc.fire & curIsFirst) {
    val wp = groupInfoRam(curGroupHead)
    wp.keepInfo := groupReqQ.bits.keepInfo
    wp.transpose := groupReqQ.bits.transpose
  }

  // DMA Buffer
  val dmaBuffer = (0 until Bank).map(i => {
    SyncReadMem(maxOutStanding, Vec(CN, genD))
  })

  // Padding FIFO
  val padFIFO = Module(new Queue(UInt(log2MaxOutStanding.W), maxOutStanding / 2, false, false, true)).io

  // Reorder Counter
  val reorderCounter = Module(new DMAReorderCounter(maxOutStanding, N))

  // Input Stream Controlling Logic
  val curIdx = Reg(UInt(log2MaxOutStanding.W))
  val curAllocatePad = curIdx > groupReqQ.bits.groupPad
  transactionAlloc.bits := curAllocateHead
  transactionAlloc.valid := curUseableEntry =/= 0.U & groupReqQ.valid & !curAllocatePad
  curAllocateHead := curAllocateHead + transactionAlloc.fire.asUInt + padFIFO.enq.fire.asUInt
  curUseableEntry := curUseableEntry - transactionAlloc.fire.asUInt - padFIFO.enq.fire.asUInt + freeEntryCount
  padFIFO.enq.bits := curAllocateHead
  padFIFO.enq.valid := curUseableEntry =/= 0.U & groupReqQ.valid & curAllocatePad

}
