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

package dayan.rvtng.fpmath.signedFP

import chisel3._
import chisel3.util._
import dayan.rvtng.fpmath._
import hardfloat._

object SignedFPFrom {
  def apply(wE: Int, wF: Int, in: UInt): SignedFP = {
    val m = Module(new FPUnpackModule(wE, wF, 0))
    m.fp := in
    val ret = Wire(new SignedFP(8, 25))
    ret.exp := m.exp
    ret.frac := m.frac.asSInt
    ret
  }
}

object FNFromSignedFP {
  def apply(in: SignedFP): UInt = {
    val m = Module(new SignedFPToFPModule(in.wE, in.wF, 0))
    m.frac := in.frac.asUInt
    m.exp := in.exp
    m.fp
  }
}

