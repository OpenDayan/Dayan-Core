package rcputils
import chisel3._
import chisel3.util._
import consts._

class FP32Multiplier extends Module {
    val io = IO(new Bundle {
        val a           = Input(UInt(32.W))
        val b           = Input(UInt(32.W))
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(Bool()) //微小值舍入前后检测
        val out          = Output(UInt(32.W))
        val exceptionFlags = Output(UInt(5.W))
    })

    val Rec_a = recFNFromFN(8, 24, io.a)
    val Rec_b = recFNFromFN(8, 24, io.b)

    val mulModule = Module(new MulRecFN(8, 24))
    mulModule.io.a := Rec_a
    mulModule.io.b := Rec_b
    mulModule.io.roundingMode   := io.roundingMode
    mulModule.io.detectTininess := io.detectTininess
    // mulModule.io.roundingMode   := consts.round_near_even
    // mulModule.io.detectTininess := consts.tininess_afterRounding

    io.out := fNFromRecFN(8, 24, mulModule.io.out)
    io.exceptionFlags := mulModule.io.exceptionFlags
}