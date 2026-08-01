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
import rvtng.bus.{TLMessages, TLUH}
import chisel3._
import chisel3.util._

// Tilelink Adapter

class DMATLReader(params: DMAParams) extends Module{
  val readReq = IO(Flipped(Decoupled(new DMAReadReq(params))))
  val readResp = IO(Decoupled(new DMAReadComp(params)))
  // tl Only Map to -> AR;R
  val tl = IO(TLUH(addressBits = params.addressBits, dataBits = params.dataBits,
    sourceBits = params.sourceBits, sinkBits = params.sinkBits, sizeBits = params.sizeBits))

  tl.a.valid := readReq.valid
  readReq.ready := tl.a.ready
  tl.a.bits.opcode := TLMessages.Get
  tl.a.bits.param := 0.U
  tl.a.bits.size  := log2Ceil(params.dataBits / 8).U
  tl.a.bits.source := Cat(0.U, readReq.bits.id)
  tl.a.bits.address := readReq.bits.addr
  tl.a.bits.mask := VecInit.tabulate(params.dataBits / 8)(i => 1.U).asUInt
  tl.a.bits.data := DontCare
  tl.a.bits.corrupt := 0.B

  readResp.bits.data := tl.d.bits.data.asTypeOf(readResp.bits.data)
  readResp.bits.tid := tl.d.bits.source
  readResp.valid := tl.d.valid
  tl.d.ready := readResp.ready
}
