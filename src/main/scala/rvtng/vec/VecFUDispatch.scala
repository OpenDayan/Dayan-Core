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

package dayan.rvtng.vec

import chisel3._
import chisel3.util._

class VecFUDispatch(params: VecParams) extends Module {
  val in = IO(Flipped(Decoupled(new VecInstRData(params))))

  val outInt = IO(Decoupled(new VecInstRData(params)))
  val outLsu = IO(Decoupled(new VecInstRData(params)))

  // TODO: Add Other FU here.
  outInt.valid := in.valid & in.bits.inst.fu === VecFuEnum.Int
  outInt.bits  := in.bits
  outLsu.valid := in.valid & in.bits.inst.fu === VecFuEnum.Lsu
  outLsu.bits  := in.bits

  // Assertion 检查，流式接口不可阻塞。
  in.ready := 1.B
  chisel3.assert(!(outInt.valid & !outInt.ready))
  chisel3.assert(!(outLsu.valid & !outLsu.ready))

}
