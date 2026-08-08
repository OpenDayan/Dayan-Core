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
import dayan.rvtng.uram.{AdvURAMReq, AdvURAMResp, SwizzleAddr}

// URAM Adapter

class DMAURAMReader(params: DMAParams) extends Module {
  val readReq = IO(Flipped(Decoupled(new DMAReadReq(params))))
  val readResp = IO(Decoupled(new DMAReadComp(params)))

  private val uramPort = params.dataBits / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  val uramReq  = IO(Vec(params.dataBits / 64, Decoupled(
    new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
  val uramResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
    new AdvURAMResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

  val issuedVec = Wire(Vec(uramChannel, Bool()))
  val issuedAll = issuedVec.asUInt.andR
  readReq.ready := issuedAll
  for(i <- 0 until uramPort) {
    val issuedMask = RegInit(0.B)
    uramReq(i).valid := readReq.valid & !issuedMask
    if((i % uramPortPerChannel) == 0) {
      // TODO: I Think the handshake logic here can be further simplified.
      issuedVec(i / uramPortPerChannel) := issuedMask | uramReq((i / uramPortPerChannel) * uramPortPerChannel).fire
    }
    issuedMask := (issuedMask | uramReq((i / uramPortPerChannel) * uramPortPerChannel).fire) & !issuedAll
    val addr = readReq.bits.addr + (i * 8).U
    uramReq(i).bits.address := SwizzleAddr(addr,
      readReq.bits.UramSwizzleS, readReq.bits.UramSwizzleB, readReq.bits.UramSwizzleM) >> 3
    uramReq(i).bits.id := readReq.bits.id
    uramReq(i).bits.byteEnable := 255.U
  }

  val respVec = Wire(Vec(uramChannel, Bool()))
  val respAll = respVec.asUInt.andR
  readResp.valid := respAll
  for(i <- 0 until uramChannel) {
    respVec(i) := uramResp(i).valid
    uramResp(i).ready := readResp.fire
  }
  readResp.bits.tid := uramResp(0).bits(0).id
  readResp.bits.data := VecInit(uramResp.map(c => VecInit(c.bits.map(_.data)))).asTypeOf(readResp.bits.data)

}
