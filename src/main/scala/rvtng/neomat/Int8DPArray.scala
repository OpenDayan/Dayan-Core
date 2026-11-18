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

package dayan.rvtng.neomat

import chisel3._
import chisel3.util._

class Int8DPArray(m: Int, n: Int, k: Int) extends Module {
  require(m > 0 && n > 0 && k > 0)

  val latency = 2
  val io = IO(new Bundle {
    val a = Input(Vec(m, Vec(k, UInt(8.W))))
    val b = Input(Vec(n, Vec(k, UInt(8.W))))
    val cin = Input(Vec(m * n, SInt(32.W)))
    val cout = Output(Vec(m * n, SInt(32.W)))
  })

  val products = RegNext(VecInit.tabulate(m, n, k) { (row, col, term) =>
    (io.a(row)(term).asSInt * io.b(col)(term).asSInt).pad(32)
  })
  val dot = RegNext(VecInit.tabulate(m, n) { (row, col) =>
    products(row)(col).reduceTree(_ + _)
  })

  for (row <- 0 until m; col <- 0 until n) {
    val index = row * n + col
    io.cout(index) := dot(row)(col) + io.cin(index)
  }
}
