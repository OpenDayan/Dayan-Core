package fpexputils

import chisel3._
import chisel3.util._

class IntAdder_28_Freq150_uid351 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(28.W))
        val Y   = Input(UInt(28.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(28.W))
    })

    val cin_delayed = ShiftRegister(io.Cin, 3)
    val X_delayed   = ShiftRegister(io.X, 1)

    val sum = X_delayed + io.Y + cin_delayed.asUInt

    io.R := sum
}
