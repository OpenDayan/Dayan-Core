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

class FixRealKCM_Freq150_uid20 extends Module {
    val io = IO(new Bundle {
        val X = Input(UInt(8.W))
        val R = Output(UInt(35.W))
    })

    // --- Instantiate all submodules ---
    val table0     = Module(new FixRealKCM_Freq150_uid20_T0_Freq150_uid23)
    val table1     = Module(new FixRealKCM_Freq150_uid20_T1_Freq150_uid26)
    val finalAdder = Module(new IntAdder_35_Freq150_uid30)

    // --- Logic and Connections ---

    // 1. Split the 8-bit input X to address the two lookup tables.
    //    Corresponds to: FixRealKCM_Freq150_uid20_A0 <= X(7 downto 3);
    table0.io.X := io.X(7, 3)

    //    Corresponds to: FixRealKCM_Freq150_uid20_A1 <= X(2 downto 0);
    table1.io.X := io.X(2, 0)

    // 2. Get the results from the lookup tables.
    val table0_out = table0.io.Y
    val table1_out = table1.io.Y

    // 3. Prepare the inputs for the final adder.
    //    The VHDL's verbose bit-by-bit reassignment is simplified here.

    //    Input 0 is the direct 35-bit output from table0.
    //    Corresponds to: bitheapFinalAdd_bh21_In0 <= "" & bh21_w34_0 & ...
    val adderInput0 = table0_out

    //    Input 1 is the 30-bit output from table1, zero-extended to 35 bits.
    //    Corresponds to: bitheapFinalAdd_bh21_In1 <= "00000" & bh21_w29_1 & ...
    val adderInput1 = Cat(0.U(5.W), table1_out)

    // 4. Connect the operands to the final adder.
    finalAdder.io.X   := adderInput0
    finalAdder.io.Y   := adderInput1
    finalAdder.io.Cin := false.B

    // 5. Connect the adder's result to the module's output.
    io.R := finalAdder.io.R
}
