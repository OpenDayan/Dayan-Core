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

package rvtng.utils

import chisel3._

// XOR Based RFRam
// 2 Read & Write Port
class XorRF2RWAsyncRam(addressWidth: Int, dataWidth: Int, selfReset: Int) extends Module {
  val mem = (0 until 2).map(i => Module(new RF1R1RWAsyncRam(
    addressWidth = addressWidth, dataWidth = dataWidth,selfReset = selfReset)))
  val wp = IO(Vec(2, new Bundle{
    val we = Input(Bool())
    val waddr = Input(UInt(addressWidth.W))
    val wdata = Input(UInt(dataWidth.W))
    val rwdata = Output(UInt(dataWidth.W))
  }))
  for(p <- 0 until 2) {
    mem(p).we := wp(p).we
    mem(p).waddr := wp(p).waddr
    mem(1-p).raddr := wp(p).waddr
    mem(p).wdata := wp(p).wdata ^ mem(1-p).rdata
    wp(p).rwdata := mem(1-p).rdata ^ mem(p).rwdata
  }
}
