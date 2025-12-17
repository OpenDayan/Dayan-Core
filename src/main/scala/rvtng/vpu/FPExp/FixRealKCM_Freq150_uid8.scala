package fpexputils

import chisel3._
import chisel3.util._

class FixRealKCM_Freq150_uid8 extends Module {
    val io = IO(new Bundle {
        val X = Input(UInt(10.W))
        val R = Output(UInt(8.W))
    })

    // --- Instantiate all submodules ---
    val table0     = Module(new FixRealKCM_Freq150_uid8_T0_Freq150_uid11)
    val table1     = Module(new FixRealKCM_Freq150_uid8_T1_Freq150_uid14)
    val finalAdder = Module(new IntAdder_12_Freq150_uid18)

    // --- Logic and Connections ---

    // 1. Split the 10-bit input X to address the two lookup tables.
    table0.io.X := io.X(9, 5)
    table1.io.X := io.X(4, 0)

    // 2. Get the results from the lookup tables.
    val table0_out = table0.io.Y // 12-bit result
    val table1_out = table1.io.Y // 7-bit result

    // 3. Prepare the inputs for the final adder by mixing bits from the table outputs.
    //    This logic directly implements the VHDL's "bitheap" wiring.

    // Input 0 is formed by concatenating the top 5 bits of table0's output
    // with all 7 bits of table1's output.
    // Corresponds to: bitheapFinalAdd_bh9_In0 <= T0(11)&..&T0(7) & T1(6)&..&T1(0)
    val adderInput0 = Cat(table0_out(11, 7), table1_out)

    // Input 1 is formed by concatenating 5 zero-bits with the bottom 7 bits
    // of table0's output.
    // Corresponds to: bitheapFinalAdd_bh9_In1 <= "00000" & T0(6)&..&T0(0)
    val adderInput1 = Cat(0.U(5.W), table0_out(6, 0))

    // 4. Connect the reconstructed operands to the final adder.
    finalAdder.io.X   := adderInput0
    finalAdder.io.Y   := adderInput1
    finalAdder.io.Cin := false.B

    // 5. Get the 12-bit sum from the adder.
    val sum_12bit = finalAdder.io.R

    // 6. Truncate the sum to the top 8 bits and assign to the output.
    // Corresponds to: R <= OutRes(11 downto 4);
    io.R := sum_12bit(11, 4)
}
