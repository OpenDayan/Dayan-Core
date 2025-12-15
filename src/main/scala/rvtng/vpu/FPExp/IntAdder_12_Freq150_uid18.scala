package fpexputils

import chisel3._
import chisel3.util._

class IntAdder_12_Freq150_uid18 extends Module {
    val io = IO(new Bundle {
        val X   = Input(UInt(12.W))
        val Y   = Input(UInt(12.W))
        val Cin = Input(Bool())
        val R   = Output(UInt(12.W))
    })

    val x_delayed   = RegNext(io.X)
    val y_delayed   = RegNext(io.Y)
    val cin_delayed = RegNext(io.Cin)

    val sum_full = x_delayed +& y_delayed + cin_delayed.asUInt

    io.R := sum_full(11, 0)
}
