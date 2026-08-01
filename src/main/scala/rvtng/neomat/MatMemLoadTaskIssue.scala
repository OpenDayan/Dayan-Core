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

package rvtng.neomat

import chisel3._
import chisel3.util._
import rvtng.utils.PartialAssign
import rvtng.uram.{AdvURAMReq, AdvURAMResp, SwizzleAddr}

class MatMemLoadTaskIssue(params: MatParams, srcCount: Int, regIdWidth: Int, posWidth: Int) extends Module {
  val ports = (params.busChannel * params.channelWidth) / 8
  val portsPerChannel = params.channelWidth / 8
  val log2SrcCount = log2Ceil(srcCount)
  private val respIdWidth = 2 + regIdWidth + posWidth + log2SrcCount
  val io = IO(new Bundle {
    // URAM 读取内部接口，全速率
    val busRReq = Vec(srcCount, Flipped(Decoupled(new MatInnerTileReadReq(params, regIdWidth, posWidth))))
    val busRResp = Output(Vec(srcCount, Vec(params.busChannel,
      new MatInnerTileChannelReadResp(params, regIdWidth, posWidth))))

    // URAM 读取外部接口，全速率
    val uramReq  = Vec(ports,
      Decoupled(new AdvURAMReq(params.addrWidth, respIdWidth, 64)))
    val uramResp = Flipped(Vec(params.busChannel,
      Decoupled(Vec(portsPerChannel, new AdvURAMResp(params.addrWidth, respIdWidth, 64)))))

    // 响应接口，完成一次请求
    val busRAck = Output(Vec(srcCount, Bool()))
  })
  // 仲裁机制，仲裁内部 URAM 请求
  val arb = Module(new RRArbiter(new MatInnerTileReadReq(params, regIdWidth, posWidth), srcCount)).io
  arb.in <> io.busRReq
  // 总线仲裁后消息包
  class MatInnerArbTileReadReq extends Bundle {
    val regId = UInt(regIdWidth.W)
    val position = UInt(posWidth.W)
    val src = UInt(log2SrcCount.W)
    val addr = UInt(params.addrWidth.W)
    val stride = UInt(params.addrWidth.W)
    val swizzle = UInt(24.W)
    val last = Bool()
    val zero = Bool()
    val byteZeroMask = UInt(params.channelWidth.W)
  }
  // 将内部 URAM 请求，分派到 8 个 Channel 的处理队列中
  val reqQ = VecInit.tabulate(params.busChannel)(i => {
    Module(new Queue(new MatInnerArbTileReadReq, 4)).io
  })
  val respInfoQ = VecInit.tabulate(params.busChannel)(i =>
    Module(new Queue(UInt(params.channelWidth.W), 16)).io
  )
  for(i <- 0 until params.busChannel) {
    val m = reqQ(i)
    m.enq.valid := arb.out.fire
    PartialAssign(m.enq.bits, arb.out.bits)
    // TODO: 适配 busChannelWidth =/= 主序长度（cK）的情况
    m.enq.bits.addr := Mux(arb.out.bits.zeroMask(i), 0.U, arb.out.bits.addr + arb.out.bits.stride * i.U)
    m.enq.bits.src := arb.chosen
    m.enq.bits.zero := arb.out.bits.zeroMask(i)
    m.enq.bits.byteZeroMask := arb.out.bits.byteZeroMask
    respInfoQ(i).enq.valid := 0.B
    respInfoQ(i).enq.bits := m.deq.bits.byteZeroMask
  }
  arb.out.ready := VecInit(reqQ.map(_.enq.ready)).reduceTree(_ & _)
  // 将 Channel 的处理队列，连接到 uram 接口
  for(i <- 0 until ports) {
    val channel = i / portsPerChannel
    val subPortId = i % portsPerChannel
    if(subPortId == 0) {
      reqQ(channel).deq.ready := io.uramReq(i).ready & respInfoQ(channel).enq.ready
      respInfoQ(channel).enq.valid := io.uramReq(i).fire
    }
    val pld = reqQ(channel).deq.bits
    io.uramReq(i).valid := reqQ(channel).deq.valid & respInfoQ(channel).enq.ready
    io.uramReq(i).bits.address := SwizzleAddr(pld.addr + (subPortId * 8).U, pld.swizzle) >> 3
    io.uramReq(i).bits.id := pld.zero ## pld.last ## pld.regId ## pld.position ## pld.src
    io.uramReq(i).bits.byteEnable := 255.U
  }
  // 完成队列
  val uramCompleteQ = VecInit.tabulate(params.busChannel)(i => Module(new Queue(UInt(log2SrcCount.W), 16)).io)
  val uramCompleteRdy = VecInit(uramCompleteQ.map(_.deq.valid)).reduceTree(_ & _)
  // 将 URAM 的返回接口，连接到 Channel 的响应端口
  for(i <- 0 until params.busChannel) {
    io.uramResp(i).ready := uramCompleteQ(i).enq.ready & respInfoQ(i).deq.valid
    val pld = io.uramResp(i).bits
    val zero = pld(0).id(log2SrcCount + posWidth + regIdWidth + 1)
    val byteZeroMask = respInfoQ(i).deq.bits
    val last = pld(0).id(log2SrcCount + posWidth + regIdWidth)
    val regId = pld(0).id(log2SrcCount + posWidth + regIdWidth - 1, log2SrcCount + posWidth)
    val pos = if(posWidth == 0) 0.U else pld(0).id(log2SrcCount + posWidth - 1, log2SrcCount)
    val src = pld(0).id(log2SrcCount - 1, 0)
    for(s <- 0 until srcCount) {
      io.busRResp(s)(i).valid := (src === s.U) & io.uramResp(i).fire
      io.busRResp(s)(i).regId := regId
      io.busRResp(s)(i).position := pos
      io.busRResp(s)(i).data := VecInit(pld.map(_.data)).asTypeOf(io.busRResp(s)(i).data)
      io.busRResp(s)(i).zero := zero
      io.busRResp(s)(i).byteZeroMask := byteZeroMask
    }
    uramCompleteQ(i).enq.valid := io.uramResp(i).fire & last
    uramCompleteQ(i).enq.bits := src
    respInfoQ(i).deq.ready := io.uramResp(i).fire
    uramCompleteQ(i).deq.ready := uramCompleteRdy
  }

  // 跟踪所有请求完成情况
  for(i <- 0 until srcCount) {
    io.busRAck(i) := (uramCompleteQ(0).deq.bits === i.U) && uramCompleteRdy
  }
}
