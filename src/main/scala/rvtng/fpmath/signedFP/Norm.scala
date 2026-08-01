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

package rvtng.fpmath.signedFP

import chisel3._
import chisel3.util._
import rvtng.fpmath._

object SignedFPNorm {
  def apply(in: SignedFP): SignedFP = {
    val ret = Wire(in.cloneType)
    // 绝对值
    val sign = in.frac(in.wF - 1)
    val frac = Mux(sign, -in.frac(in.wF - 2, 0), in.frac(in.wF - 2, 0))
    // 统计前导零数目
    val clz = CLZ(frac)
    ret.exp := in.exp - clz.zext
    ret.frac := in.frac << clz
    ret
  }
}
