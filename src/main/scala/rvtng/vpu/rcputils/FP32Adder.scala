package rcputils
import chisel3._
import chisel3.util._
import consts._

class FP32Adder extends Module {
    val io = IO(new Bundle {
        val subOp          = Input(Bool())
        val a           = Input(UInt(32.W))
        val b           = Input(UInt(32.W))
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(Bool()) //微小值舍入前后检测
        val out          = Output(UInt(32.W))
        val exceptionFlags = Output(UInt(5.W))
    })

    val Rec_a = recFNFromFN(8, 24, io.a)
    val Rec_b = recFNFromFN(8, 24, io.b)

    val addModule = Module(new AddRecFN(8, 24))
    addModule.io.subOp := io.subOp
    addModule.io.a := Rec_a
    addModule.io.b := Rec_b
    addModule.io.roundingMode := io.roundingMode
    addModule.io.detectTininess := io.detectTininess
    // addModule.io.roundingMode := consts.round_near_even
    // addModule.io.detectTininess := consts.tininess_afterRounding

    io.out := fNFromRecFN(8, 24, addModule.io.out)
    io.exceptionFlags := addModule.io.exceptionFlags
}