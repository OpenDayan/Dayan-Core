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

package dayan.rvtng.utils

import chisel3._
import chisel3.util._

class UnsignedMulBase(AWIDTH: Int = 27, BWIDTH: Int = 18) extends
  BlackBox(Map("AWIDTH" -> AWIDTH, "BWIDTH" -> BWIDTH))
  with HasBlackBoxResource {

  val io = IO(new Bundle {
    val a = Input(UInt(AWIDTH.W))
    val b = Input(UInt(BWIDTH.W))
    val c = Output(UInt((AWIDTH+BWIDTH).W))
  })
  addResource("/verilog/UnsignedMulBase.sv")
}
