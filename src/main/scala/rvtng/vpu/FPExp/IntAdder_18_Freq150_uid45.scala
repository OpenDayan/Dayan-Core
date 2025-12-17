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
