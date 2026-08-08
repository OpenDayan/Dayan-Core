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

package dayan.rvtng.neomat

import dayan.rvtng.utils.RF1R1RWAsyncRamGen
import chisel3._
import chisel3.util._
import dayan.rvtng.top.{TensorInst, TensorResp}

class MatCSRStorage(params: MatParams) extends Bundle {
  // spec Table 4.1 对应的新寄存器实现，用别名保留原有命名
  val C = UInt(params.MNKWidth.W)                // offset 0
  val Wi = UInt(params.MNKWidth.W)               // offset 1
  val Hi = UInt(params.MNKWidth.W)               // offset 2
  val Wo = UInt(params.MNKWidth.W)               // offset 3
  val Ho = UInt(params.MNKWidth.W)               // offset 4
  val Wk = UInt(params.MNKWidth.W)               // offset 5
  val Hk = UInt(params.MNKWidth.W)               // offset 6
  val Co = UInt(params.MNKWidth.W)               // offset 7
  val WiStride = UInt(params.addrWidth.W)        // offset 8
  val HiStride = UInt(params.addrWidth.W)        // offset 9
  val WkStride = UInt(params.addrWidth.W)        // offset 10
  val HkStride = UInt(params.addrWidth.W)        // offset 11
  val CoStride = UInt(params.addrWidth.W)        // offset 12
  val WoStride = UInt(params.addrWidth.W)        // offset 13
  val HoStride = UInt(params.addrWidth.W)        // offset 14
  val ISwizzleMBS = UInt(24.W)                   // offset 15
  val KSwizzleMBS = UInt(24.W)                   // offset 16
  val OSwizzleMBS = UInt(24.W)                   // offset 17
  val DataType = UInt(8.W)                       // offset 18
  val ScaleWiStride = UInt(params.addrWidth.W)   // offset 19
  val ScaleHiStride = UInt(params.addrWidth.W)   // offset 20
  val ScaleWkStride = UInt(params.addrWidth.W)   // offset 21
  val ScaleHkStride = UInt(params.addrWidth.W)   // offset 22
  val ScaleCoStride = UInt(params.addrWidth.W)   // offset 23
  val PadL = UInt(params.MNKWidth.W)             // offset 24
  val PadU = UInt(params.MNKWidth.W)             // offset 25
  val DilationW = UInt(params.MNKWidth.W)        // offset 26
  val DilationH = UInt(params.MNKWidth.W)        // offset 27
  val StrideW = UInt(params.MNKWidth.W)          // offset 28
  val StrideH = UInt(params.MNKWidth.W)          // offset 29
  val StrideDilationW = UInt(params.addrWidth.W) // offset 30
  val StrideDilationH = UInt(params.addrWidth.W) // offset 31

  // GEMM aliases
  def K: UInt = C
  def M: UInt = Wo
  def N: UInt = Co
  def AStride: UInt = WiStride
  def BStride: UInt = CoStride
  def CStride: UInt = WoStride
  def ASwizzleMBS: UInt = ISwizzleMBS
  def BSwizzleMBS: UInt = KSwizzleMBS
  def CSwizzleMBS: UInt = OSwizzleMBS
  def AScaleStride: UInt = ScaleWiStride
  def BScaleStride: UInt = ScaleCoStride
  def PersistentMode: UInt = 0.U(2.W)

  // CONV aliases
  def taskCi: UInt = C
  def taskWi: UInt = Wi
  def taskHi: UInt = Hi
  def taskWo: UInt = Wo
  def taskHo: UInt = Ho
  def taskWk: UInt = Wk
  def taskHk: UInt = Hk
  def taskCo: UInt = Co
  def wiStride: UInt = WiStride
  def hiStride: UInt = HiStride
  def wkStride: UInt = WkStride
  def hkStride: UInt = HkStride
  def coStride: UInt = CoStride
  def woStride: UInt = WoStride
  def hoStride: UInt = HoStride
  def padL: UInt = PadL
  def padU: UInt = PadU
  def dilationW: UInt = DilationW
  def dilationH: UInt = DilationH
  def strideW: UInt = StrideW
  def strideH: UInt = StrideH
}

class MatCSRGroupFetchIF(params: MatParams) extends Bundle {
  val addr = Input(UInt(log2Ceil(params.tcsrBanks).W))
  val ctrl = Output(new MatCSRStorage(params))
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
  val io = IO(new Bundle{
      val instReq = Flipped(Decoupled(new TensorInst()))
      val instResp = Decoupled(new TensorResp())
      val sel = new MatCSRGroupFetchIF(params)
  })
  private val instReq = io.instReq
  private val instResp = io.instResp
  private val sel = io.sel
  private val instRespRaw = Wire(DecoupledIO(new TensorResp()))
  instResp <> Queue(instRespRaw, 2, false, false)

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

  private val csrBankWidth = log2Ceil(params.tcsrBanks)
  private val csrBank = instDecode.bank(csrBankWidth - 1, 0)
  private val csrBankValid = instDecode.bank < params.tcsrBanks.U

  private def mapCSR(offset: Int, field: UInt): Unit = {
    val mem = Module(new RF1R1RWAsyncRamGen(csrBankWidth, 0, chiselTypeOf(field)))
    mem.raddr := sel.addr
    field := mem.rdata
    mem.waddr := csrBank
    mem.we := instDecode.we & csrBankValid & (instDecode.subAddr === offset.U)
    mem.wdata := instDecode.wData.asTypeOf(chiselTypeOf(field))
    when(csrBankValid & (instDecode.subAddr === offset.U)) {
      instRespRaw.bits.data := mem.rwdata.asUInt
    }
  }

  mapCSR(0,  sel.ctrl.C)
  mapCSR(1,  sel.ctrl.Wi)
  mapCSR(2,  sel.ctrl.Hi)
  mapCSR(3,  sel.ctrl.Wo)
  mapCSR(4,  sel.ctrl.Ho)
  mapCSR(5,  sel.ctrl.Wk)
  mapCSR(6,  sel.ctrl.Hk)
  mapCSR(7,  sel.ctrl.Co)
  mapCSR(8,  sel.ctrl.WiStride)
  mapCSR(9,  sel.ctrl.HiStride)
  mapCSR(10, sel.ctrl.WkStride)
  mapCSR(11, sel.ctrl.HkStride)
  mapCSR(12, sel.ctrl.CoStride)
  mapCSR(13, sel.ctrl.WoStride)
  mapCSR(14, sel.ctrl.HoStride)
  mapCSR(15, sel.ctrl.ISwizzleMBS)
  mapCSR(16, sel.ctrl.KSwizzleMBS)
  mapCSR(17, sel.ctrl.OSwizzleMBS)
  mapCSR(18, sel.ctrl.DataType)
  mapCSR(19, sel.ctrl.ScaleWiStride)
  mapCSR(20, sel.ctrl.ScaleHiStride)
  mapCSR(21, sel.ctrl.ScaleWkStride)
  mapCSR(22, sel.ctrl.ScaleHkStride)
  mapCSR(23, sel.ctrl.ScaleCoStride)
  mapCSR(24, sel.ctrl.PadL)
  mapCSR(25, sel.ctrl.PadU)
  mapCSR(26, sel.ctrl.DilationW)
  mapCSR(27, sel.ctrl.DilationH)
  mapCSR(28, sel.ctrl.StrideW)
  mapCSR(29, sel.ctrl.StrideH)
  mapCSR(30, sel.ctrl.StrideDilationW)
  mapCSR(31, sel.ctrl.StrideDilationH)
}
