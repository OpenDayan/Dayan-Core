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
import chisel3.util._

// Fake DPIRam with fixed 32b address and 64b data
class DPIRamBase extends BlackBox
  with HasBlackBoxResource {

    val io = IO(new Bundle {
      val id = Input(UInt(32.W))
      val clk = Input(Clock())
      val reset = Input(Reset())

      val re = Input(Bool())
      val raddr = Input(UInt(32.W))
      val rdata = Output(UInt(64.W))

      val we = Input(Bool())
      val waddr = Input(UInt(32.W))
      val wdata = Input(UInt(64.W))
    })
    addResource("/verilog/DPIRamBase.sv")
}
