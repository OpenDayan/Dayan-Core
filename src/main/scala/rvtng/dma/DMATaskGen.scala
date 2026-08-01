/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package rvtng.dma

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import rvtng.top.TensorInst

class DMAWriteTaskInfo(params: DMAParams) extends Bundle{
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  val addr = UInt(params.addressBits.W) // Block Base Address
  val block = UInt(8.W)
  val stride = UInt(24.W) // Request Stride
  val isDRAM = Bool()
  val isLastInst = Bool()    // Last Block Request for a inst
  // For URAMReader Swizzle algorithm
  val UramSwizzleS = UInt(8.W)
  val UramSwizzleB = UInt(8.W)
  val UramSwizzleM = UInt(8.W)
}

class DMAReadReq(params: DMAParams) extends Bundle {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  val addr = UInt(params.addressBits.W) // Address Should be Aligned to 64B in DRAM Access, Aligned to 8B in URAM Access
  val id   = UInt(log2MaxOutStanding.W) // Outstanding Transaction ID
  val isDRAM = Bool()
  // For URAMReader Swizzle algorithm
  val UramSwizzleS = UInt(8.W)
  val UramSwizzleB = UInt(8.W)
  val UramSwizzleM = UInt(8.W)
}

class DMAGroupReq(params: DMAParams) extends Bundle {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  private val genD: UInt = params.byteType
  private val N  = 1 << log2N
  private val CN = 1 << log2CN
  private val log2BANK = log2N - log2CN
  private val BANK = N / CN
  private val maxOutStanding = 1 << log2MaxOutStanding

  val keepInfo  = new DMAWriteTaskInfo(params)
  val groupSz   = UInt(log2MaxOutStanding.W) // GroupSize == [64B / (2^transpose)], Spilt Inside
  val groupPad  = UInt(log2MaxOutStanding.W)
  val reqPad    = UInt(log2N.W)
  val padValue  = Vec(CN, genD)
  val transpose = UInt(log2Ceil(log2N).W) // 64 -> 0, 1, 2, 3, 4, 5
}


/*
* 需求：
*    本模块用于将一条 DMA 指令，拆分为若干 DMA 事务并发送给相应模块执行。
*
* 端口：
*    groupCtrlIf 链接到 DMACSR 模块
*    instReq 链接到 IssueQueue
*    groupReq 链接到 DMAReorderTransposeBuffer
*    readReq 链接到 TLReader / URAMReader
*    transactionAlloc 链接到 DMAReorderTransposeBuffer
*
* 实现：
*    主结构为一个计数器支持的地址 / 子任务生成循环体。循环采用倒序循环实现，反方向循环。
* 每个 Request 发出一次 readReq， 每个 Request Group 发射一次 groupReq。
*
* */

class DMAMergedInst(params: DMAParams) extends Bundle {
  val csr = new DMACSRStorage()
  val src = UInt(params.addressBits.W)
  val dst = UInt(params.addressBits.W)
  val srcDRAM = Bool()
  val dstDRAM = Bool()
}

class DMATaskGen(params: DMAParams) extends Module {
  private val log2MaxOutStanding: Int = params.sourceBits - 1
  private val log2N: Int = log2Ceil(params.dataBits / params.byteType.getWidth)
  private val log2CN: Int = params.transposeLog2CNSize // Fixed for now?
  private val genD: UInt = params.byteType

  private val N  = 1 << log2N
  private val CN = 1 << log2CN
  private val log2BANK = log2N - log2CN
  private val BANK = N / CN
  private val maxOutStanding = 1 << log2MaxOutStanding
  val groupCtrlIf = IO(Flipped(new DMACSRGroupFetchIF(params)))
  val instReq = IO(Flipped(Decoupled(new TensorInst())))

  val groupReq = IO(Decoupled(new DMAGroupReq(params)))
  val readReq = IO(Decoupled(new DMAReadReq(params)))
  val transactionAlloc = IO(Flipped(Decoupled(UInt(log2MaxOutStanding.W))))

  // TODO: FIFO Size
  private val noIdReadReqQ = Module(new Queue(new DMAReadReq(params), 4, false, false)).io
  readReq.bits := noIdReadReqQ.deq.bits
  readReq.bits.id := transactionAlloc.bits
  // Channel Mixing
  readReq.valid := noIdReadReqQ.deq.valid & transactionAlloc.valid
  noIdReadReqQ.deq.ready := readReq.ready & transactionAlloc.valid
  transactionAlloc.ready := noIdReadReqQ.deq.valid & readReq.ready

  // TODO: FIFO Entry Optimization.
  // Merge Raw Inst(Direction and base address) and TCSR Info
  private val mergeInstQ = Module(new Queue(new DMAMergedInst(params), 8, false, false)).io

  // Connection Of Inst Issued Port
  mergeInstQ.enq.bits.csr := groupCtrlIf.ctrl
  mergeInstQ.enq.bits.src := instReq.bits.rs1Data
  mergeInstQ.enq.bits.dst := instReq.bits.rdData
  mergeInstQ.enq.bits.srcDRAM := instReq.bits.instWord(13).asBool
  mergeInstQ.enq.bits.dstDRAM := instReq.bits.instWord(12).asBool
  mergeInstQ.enq.valid := instReq.valid
  instReq.ready := mergeInstQ.enq.ready
  groupCtrlIf.addr := instReq.bits.instWord(22, 20)

  // Main Loop: Address Generator
  private val curInfo  = RegEnable(mergeInstQ.deq.bits, mergeInstQ.deq.fire)
  private val curValid = RegInit(0.B) // Fully pipelined

  private val curBlock   = Reg(UInt(8.W))
  private val curBlockCr = curBlock === curInfo.csr.Block
  private val curBlockFirst = curBlock === 0.U
  private val curDim0    = Reg(UInt(24.W))
  private val curDim0Cr  = curDim0 === curInfo.csr.Dim0 & curBlockCr
  private val curDim1    = Reg(UInt(24.W))
  private val curDim1Cr  = curDim1 === curInfo.csr.Dim1 & curDim0Cr
  private val curDim2    = Reg(UInt(24.W))
  private val curDim2Cr  = curDim2 === curInfo.csr.Dim2 & curDim1Cr // Last Signal
  private val curInBlockBase = Reg(UInt(params.addressBits.W))
  private val curInDim0Base  = Reg(UInt(params.addressBits.W))
  private val curInDim1Base  = Reg(UInt(params.addressBits.W))
  private val curInDim2Base  = Reg(UInt(params.addressBits.W))
  private val nxtInDim2Base  = curInDim2Base  + curInfo.csr.InDim2Stride
  private val nxtInDim1Base  = Mux(curDim1Cr, nxtInDim2Base, curInDim1Base  + curInfo.csr.InDim1Stride)
  private val nxtInDim0Base  = Mux(curDim0Cr, nxtInDim1Base, curInDim0Base  + curInfo.csr.InDim0Stride)
  private val nxtInBlockBase = Mux(curBlockCr, nxtInDim0Base, curInBlockBase + curInfo.csr.InBlockStride)
  private val curOutBlockBase = Reg(UInt(params.addressBits.W))
  private val curOutDim0Base  = Reg(UInt(params.addressBits.W))
  private val curOutDim1Base  = Reg(UInt(params.addressBits.W))
  private val curOutDim2Base  = Reg(UInt(params.addressBits.W))
  private val nxtOutDim2Base  = curOutDim2Base  + curInfo.csr.OutDim2Stride
  private val nxtOutDim1Base  = Mux(curDim1Cr, nxtOutDim2Base, curOutDim1Base  + curInfo.csr.OutDim1Stride)
  private val nxtOutDim0Base  = Mux(curDim0Cr, nxtOutDim1Base, curOutDim0Base  + curInfo.csr.OutDim0Stride)
  private val nxtOutBlockBase = Mux(curBlockCr, nxtOutDim0Base, curOutBlockBase + curInfo.csr.OutBlockStride)
  when(mergeInstQ.deq.fire) {
    // New Request.
    curValid := 1.B
    curBlock := 0.U
    curDim0  := 0.U
    curDim1  := 0.U
    curDim2  := 0.U
    curInBlockBase := mergeInstQ.deq.bits.src
    curInDim0Base  := mergeInstQ.deq.bits.src
    curInDim1Base  := mergeInstQ.deq.bits.src
    curInDim2Base  := mergeInstQ.deq.bits.src
    curOutBlockBase := mergeInstQ.deq.bits.dst
    curOutDim0Base  := mergeInstQ.deq.bits.dst
    curOutDim1Base  := mergeInstQ.deq.bits.dst
    curOutDim2Base  := mergeInstQ.deq.bits.dst
  }.elsewhen(noIdReadReqQ.enq.fire) {
    curInBlockBase := nxtInBlockBase
    curOutBlockBase := nxtOutBlockBase
    when(curDim1Cr) {
      curDim1 := 0.U
      curInDim2Base := nxtInDim2Base
      curOutDim2Base := nxtOutDim2Base
      curDim2 := curDim2 + 1.U
    }.elsewhen(curDim0Cr) {
      curDim1 := curDim1 + 1.U
    }
    when(curDim0Cr) {
      curDim0 := 0.U
      curInDim1Base := nxtInDim1Base
      curOutDim1Base := nxtOutDim1Base
    }.elsewhen(curBlockCr) {
      curDim0 := curDim0 + 1.U
    }
    when(curBlockCr) {
      curBlock := 0.U
      curInDim0Base := nxtInDim0Base
      curOutDim0Base := nxtOutDim0Base
    }.otherwise {
      curBlock := curBlock + 1.U
    }
    when(curDim2Cr) {
      curValid := 0.B
    }
  }
  mergeInstQ.deq.ready := !curValid | (curDim2Cr & noIdReadReqQ.enq.fire)

  // Read Request Generator
  noIdReadReqQ.enq.bits.addr := curInBlockBase
  noIdReadReqQ.enq.bits.isDRAM := curInfo.srcDRAM
  noIdReadReqQ.enq.bits.UramSwizzleS := curInfo.csr.UramReadSwizzleMBS( 7,  0)
  noIdReadReqQ.enq.bits.UramSwizzleB := curInfo.csr.UramReadSwizzleMBS(15,  8)
  noIdReadReqQ.enq.bits.UramSwizzleM := curInfo.csr.UramReadSwizzleMBS(23, 16)
  noIdReadReqQ.enq.bits.id := DontCare
  noIdReadReqQ.enq.valid := curValid & (!curBlockFirst | groupReq.ready)
  // Group Request Generator
  groupReq.valid := curValid & curBlockFirst & noIdReadReqQ.enq.ready
  groupReq.bits.groupSz   := curInfo.csr.Block
  groupReq.bits.groupPad  := curInfo.csr.BlockPadding
  groupReq.bits.reqPad    := curInfo.csr.RequestPadding
  groupReq.bits.padValue  := curInfo.csr.PaddingValue.asTypeOf(groupReq.bits.padValue)
  groupReq.bits.transpose := curInfo.csr.BlockTranspose
  // Write Request Generator
  groupReq.bits.keepInfo.addr := curOutDim0Base
  groupReq.bits.keepInfo.block  := curInfo.csr.Block
  groupReq.bits.keepInfo.stride := curInfo.csr.OutBlockStride
  groupReq.bits.keepInfo.isDRAM := curInfo.dstDRAM
  groupReq.bits.keepInfo.isLastInst :=
    (curDim2 === curInfo.csr.Dim2) & (curDim1 === curInfo.csr.Dim1) & (curDim0 === curInfo.csr.Dim0)
  groupReq.bits.keepInfo.UramSwizzleS := curInfo.csr.UramWriteSwizzleMBS( 7,  0)
  groupReq.bits.keepInfo.UramSwizzleB := curInfo.csr.UramWriteSwizzleMBS(15,  8)
  groupReq.bits.keepInfo.UramSwizzleM := curInfo.csr.UramWriteSwizzleMBS(23, 16)
}
