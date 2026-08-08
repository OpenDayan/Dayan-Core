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

package dayan.rvtng

import chisel3._

package object utils {
  // Input: x, shamt
  // Output: Shifted Value, sticky bit
  def genPipe[T <: Data](x: T, stage: Int): Vec[T] = {
    val pipe = Wire(Vec(stage + 1, UInt(x.getWidth.W)))
    pipe(0) := x.asUInt
    (1 to stage).foreach(index => pipe(index) := RegNext(pipe(index - 1)))
    VecInit.tabulate(stage + 1)(pipe(_).asTypeOf(x))
  }
}