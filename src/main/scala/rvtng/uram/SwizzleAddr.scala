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

package dayan.rvtng.uram

import chisel3._
import chisel3.util._

object SwizzleAddr {
  def apply(addr: UInt, S: UInt, B: UInt, M: UInt, maxMask : Int = 8): UInt = {
    require(addr.getWidth > maxMask)
    val shiftedAddr = (addr >> S).asUInt(addr.getWidth - 1, 0)
    val mask = Cat(0.U((addr.getWidth - maxMask).W), ((1.U(maxMask.W) << B).asUInt - 1.U).asUInt(addr.getWidth - 1, 0))
    val shiftedMask = (mask << M).asUInt(addr.getWidth - 1, 0)
    addr ^ (shiftedAddr & shiftedMask)
  }
  def apply(addr: UInt, MBS: UInt, maxMask: Int): UInt = {
    val M = MBS(23, 16)
    val B = MBS(15,  8)
    val S = MBS( 7,  0)
    SwizzleAddr(addr, S, B, M, maxMask)
  }
  def apply(addr: UInt, MBS: UInt): UInt = {
    val M = MBS(23, 16)
    val B = MBS(15,  8)
    val S = MBS( 7,  0)
    SwizzleAddr(addr, S, B, M)
  }
}
