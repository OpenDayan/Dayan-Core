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

package fpexputils

import chisel3._
import chisel3.util._

class IntAdder_18_Freq150_uid45 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(18.W))
        val Y   = Input(UInt(18.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(18.W))
    })

    val cin_delayed = ShiftRegister(io.Cin, 3)
    val X_delayed   = ShiftRegister(io.X, 1)
    val Y_delayed   = ShiftRegister(io.Y, 3)

    val sum = X_delayed + Y_delayed + cin_delayed.asUInt

    io.R := sum
}
