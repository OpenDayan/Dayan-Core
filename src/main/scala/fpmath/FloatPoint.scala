package fpmath

import chisel3._
import chisel3.util._

class FP8E4M3 extends Bundle {
  val s = UInt(1.W)
  val f = UInt(4.W)
  val m = UInt(3.W)
}

class FP8E5M2 extends Bundle {
  val s = UInt(1.W)
  val f = UInt(4.W)
  val m = UInt(3.W)
}

class BF16 extends Bundle {
  val s = UInt(1.W)
  val f = UInt(8.W)
  val m = UInt(7.W)
}

// Unpack to FP8 and BF16
class FP8Unpack extends Bundle {
  val s = Bool()
  val f = UInt(4.W)
  val m = UInt(4.W)  // [1/0, mantissa]
}

class BF16Unpack extends Bundle {
  val s = Bool()
  val f = UInt(8.W)
  val m = UInt(8.W) // [1/0, mantissa]
}

package object converter {
  val func_unpack_e4m3 = (input:UInt, index: Int) => {
    val fp8Val = Wire(new FP8Unpack)
    // E4M3 S7 E6 5 4 3 M2 1 0
    val rawSign = input(8 * index + 7)
    val rawFrac = input(8 * index + 6, 8 * index + 3)
    val rawMantissa = Cat(Mux(rawFrac === 0.U, 0.U(1.W), 1.U(1.W)) ,input(8 * index + 2, 8 * index + 0))
    fp8Val.s := rawSign
    fp8Val.f := rawFrac
    fp8Val.m := rawMantissa
    fp8Val
  }
  val func_unpack_bf16 = (input:UInt, index: Int) => {
    val bf16Val = Wire(new BF16Unpack)
    // BF16 S15 E14 13 12 11 10 9 8 7 M6 5 4 3 2 1 0
    val rawSign = input(16 * index + 15)
    val rawFrac = input(16 * index + 14, 16 * index + 7)
    val rawMantissa = Cat(Mux(rawFrac === 0.U, 0.U(1.W), 1.U(1.W)) ,input(16 * index + 6, 16 * index + 0))
    bf16Val.s := rawSign
    bf16Val.f := rawFrac
    bf16Val.m := rawMantissa
    bf16Val
  }

  // Input: x, shamt
  // Output: Shifted Value, sticky bit
  val shiftRight = (x: UInt, shamt_raw: UInt) => {
    val len = x.getWidth
    val max_shift_width = log2Up(len)
    val exceed_max_shift = shamt_raw > len.U
    val shamt = shamt_raw(max_shift_width - 1, 0)
    val sticky_mask =
      ((1.U << shamt).asUInt - 1.U)(len - 1, 0) | Fill(len, exceed_max_shift)
    Tuple2(Mux(exceed_max_shift, 0.U, x >> shamt_raw), (x & sticky_mask).orR)
  }

}
