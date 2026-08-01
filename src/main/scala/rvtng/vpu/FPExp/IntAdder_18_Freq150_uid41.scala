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

/** Chisel translation of the VHDL entity IntAdder_18_Freq150_uid41.
  *
  * This module computes R = X + Y + Cin_delayed. The X and Y inputs are used combinationally, while the Cin input is
  * registered for one clock cycle before being used in the addition.
  */
class IntAdder_18_Freq150_uid41 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(18.W))
        val Y   = Input(UInt(18.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(18.W))
    })

    // 1. Register the Cin input to delay it by one clock cycle.
    //    This corresponds to the VHDL process block:
    //    Cin_d1 <= Cin;
    val cin_delayed = RegNext(io.Cin)

    // 2. Perform the addition using the current X and Y, and the delayed Cin.
    //    The .asUInt converts the Bool type to a 1-bit UInt for the calculation.
    //    This corresponds to the VHDL signal assignment:
    //    Rtmp <= X + Y + Cin_d1;
    val sum = io.X + io.Y + cin_delayed.asUInt

    // 3. Assign the result to the output port.
    io.R := sum
}
