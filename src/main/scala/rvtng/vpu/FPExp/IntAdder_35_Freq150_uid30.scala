package fpexputils

import chisel3._
import chisel3.util._

class IntAdder_35_Freq150_uid30 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(35.W))
        val Y   = Input(UInt(35.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(35.W))
    })

    val cin_delayed = RegNext(io.Cin)

    val sum = io.X + io.Y + cin_delayed.asUInt

    io.R := sum
}
