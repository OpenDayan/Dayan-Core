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
