package fpexputils

import chisel3._
import chisel3.util._

class IntMultiplier_17x18_19_Freq150_uid47 extends Module {
    val io = IO(new Bundle {
        val X = Input(UInt(17.W))
        val Y = Input(UInt(18.W))
        val R = Output(UInt(19.W))
    })

    val Y_delayed = ShiftRegister(io.Y, 2)
    io.R := (io.X * Y_delayed)(34, 16)
}
