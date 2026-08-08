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

package dayan.rvtng.dma

import _root_.circt.stage.ChiselStage
import dayan.rvtng.utils.RF1R1RWAsyncRamGen
import dayan.rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

// Tilelink Adapter

class DMATLWriter(params: DMAParams) extends Module{
  val writeReq = IO(Flipped(Decoupled(new DMAWriteReq(params))))
  val writeResp = IO(Decoupled(UInt(params.log2MaxOutStanding.W)))
  // tl Only Map to -> AW;W;B
  val tl = IO(TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = params.sizeBits))

  tl.a.valid := writeReq.valid
  writeReq.ready := tl.a.ready
  tl.a.bits.opcode := TLMessages.PutFullData
  tl.a.bits.param := 0.U
  tl.a.bits.size  := log2Ceil(params.dataBits / 8).U
  tl.a.bits.source := Cat(1.U, writeReq.bits.id)
  tl.a.bits.address := writeReq.bits.addr
  tl.a.bits.mask := VecInit.tabulate(params.dataBits / 8)(i => 1.U).asUInt
  tl.a.bits.data := writeReq.bits.data.asUInt
  tl.a.bits.corrupt := 0.B

  writeResp.bits := tl.d.bits.source(params.log2MaxOutStanding - 1, 0)
  writeResp.valid := tl.d.valid
  tl.d.ready := writeResp.ready
}
