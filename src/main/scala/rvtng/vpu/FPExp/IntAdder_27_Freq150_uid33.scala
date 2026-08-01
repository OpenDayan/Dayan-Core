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

/** A direct Chisel translation of the VHDL entity IntAdder_27_Freq150_uid33.
  *
  * This adder calculates R = (X_delayed_one_cycle + Y_current_cycle + Cin_delayed_one_cycle). It registers the 'X' and
  * 'Cin' inputs but uses the 'Y' input combinationally. The addition is performed on 28-bit operands (zero-extended) to
  * capture the carry-out, and the final result is truncated back to 27 bits.
  */
class IntAdder_27_Freq150_uid33 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(27.W))
        val Y   = Input(UInt(27.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(27.W))
    })

    // 1. Register the X and Cin inputs to match the VHDL pipeline stage.
    //    VHDL: X_1_d1 <= '0' & X; Cin_1_d1 <= Cin;
    val x_delayed   = RegNext(io.X)
    val cin_delayed = RegNext(io.Cin)

    // 2. Perform the addition.
    //    The VHDL extends operands to 28 bits before adding to handle the carry.
    //    We replicate this using .zext (zero-extend).
    //    Note that Y is used combinationally from the current cycle's input (io.Y).
    //    VHDL: S_1 <= X_1_d1 + Y_1 + Cin_1_d1;
    val sum_full = x_delayed +& io.Y +& cin_delayed.asUInt

    // 3. Truncate the 28-bit sum to the 27-bit output.
    //    This discards the final carry-out bit, matching the VHDL behavior.
    //    VHDL: R_1 <= S_1(26 downto 0);
    io.R := sum_full(26, 0)
}
