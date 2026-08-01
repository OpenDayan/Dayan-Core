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
  val UramWriteSwizzleMBS = UInt(24.W)
  val UramReadSwizzleMBS  = UInt(24.W)
}

class DMACSRGroupFetchIF(params: DMAParams) extends Bundle {
  val addr = Input(UInt(log2Ceil(params.tcsrBanks).W))
  val ctrl = Output(new DMACSRStorage)
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
  val groupCtrlIf = IO(new DMACSRGroupFetchIF(params))

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

