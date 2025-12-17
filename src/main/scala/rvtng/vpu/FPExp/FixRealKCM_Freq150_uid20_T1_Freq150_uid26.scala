package fpexputils

import chisel3._
import chisel3.util._

class FixRealKCM_Freq150_uid20_T1_Freq150_uid26 extends Module {
    val io   = IO(new Bundle {
        val X = Input(UInt(3.W))
        val Y = Output(UInt(30.W))
    })
    val Y0   = Wire(UInt(30.W))
    val Y1   = Wire(UInt(30.W))
    val data = Array(
      ("000", "000000000000000000000000000000"),
      ("001", "000101100010111001000011000000"),
      ("010", "001011000101110010000101111111"),
      ("011", "010000101000101011001000111111"),
      ("100", "010110001011100100001011111111"),
      ("101", "011011101110011101001110111111"),
      ("110", "100001010001010110010001111110"),
      ("111", "100110110100001111010100111110")
    ).map { case (a, b) => (("b" + a).U(3.W), ("b" + b).U(30.W)) }.toIndexedSeq
    Y0 := MuxLookup(io.X, "b000000000000000000000000000000".U)(data)
    Y1   := Y0
    io.Y := Y1
}
