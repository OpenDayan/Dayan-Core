package fpmath

import chisel3._
import chisel3.util._

trait U8U4FusedMxNyKzMul {
  val latency: Int
  val a_u4:Vec[Vec[UInt]]
  val b_u4:Vec[Vec[UInt]]
  val a_u8:Vec[UInt]
  val b_u8:Vec[UInt]
  val u8: Bool
  val u4_out: Vec[Vec[Vec[UInt]]]
  val u8_out: Vec[Vec[UInt]]
}

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

class CLZ(len: Int, zero: Boolean) extends Module {

  val inWidth = len
  val outWidth = (inWidth - 1).U.getWidth

  val io = IO(new Bundle() {
    val in = Input(UInt(inWidth.W))
    val out = Output(UInt(outWidth.W))
  })

  io.out := PriorityEncoder(io.in.asBools.reverse)
}

object CLZ {
  def apply(value: UInt): UInt = {
    val clz = Module(new CLZ(value.getWidth, true))
    clz.io.in := value
    clz.io.out
  }
  def apply(xs: Seq[Bool]): UInt = {
    apply(Cat(xs.reverse))
  }
}

package object converter {
  val func_unpack_e4m3 = (input:UInt) => {
    val fp8Val = Wire(new FP8Unpack)
    // E4M3 S7 E6 5 4 3 M2 1 0
    val rawSign = input(7)
    val rawFrac = input(6, 3)
    val rawMantissa = Cat(Mux(rawFrac === 0.U, 0.U(1.W), 1.U(1.W)) ,input(2, 0))
    fp8Val.s := rawSign
    fp8Val.f := rawFrac
    fp8Val.m := rawMantissa
    fp8Val
  }
  val func_unpack_bf16 = (input:UInt) => {
    val bf16Val = Wire(new BF16Unpack)
    // BF16 S15 E14 13 12 11 10 9 8 7 M6 5 4 3 2 1 0
    val rawSign = input(15)
    val rawFrac = input(14, 7)
    val rawMantissa = Cat(Mux(rawFrac === 0.U, 0.U(1.W), 1.U(1.W)) ,input(6, 0))
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
  val shiftRightSigned = (x: SInt, shamt_raw: UInt) => {
    val len = x.getWidth
    val max_shift_width = log2Up(len)
    val exceed_max_shift = shamt_raw > len.U
    val shamt = shamt_raw(max_shift_width - 1, 0)
    val sticky_mask =
      ((1.U << shamt).asUInt - 1.U)(len - 1, 0) | Fill(len, exceed_max_shift)
    Tuple2(Mux(exceed_max_shift, 0.S, x >> shamt_raw), (x.asUInt & sticky_mask).orR.asSInt)
  }

  val getMaxUInt = (width: Int) => (1.toLong << width).U - 1.U
  val getMaxSInt = (width: Int) => (1.toLong << (width - 1)).S - 1.S
  val getMinSInt = (width: Int) => (1.toLong << (width - 1)).S

  val clipOpSS = (op: (SInt, SInt) => SInt, x: SInt, y: SInt, upperBound: SInt, lowerBound: SInt) => {
    val raw = op(x, y)
    val r = Mux(raw > upperBound, upperBound,
      Mux(raw < lowerBound, lowerBound, raw))
    r
  }

  val clipAdd = (x: SInt, y: SInt, upperBound: SInt, lowerBound: SInt) => {
    clipOpSS((a: SInt, b: SInt) => a +& b, x, y, upperBound, lowerBound)
  }

  val clipSub = (x: SInt, y: SInt, upperBound: SInt, lowerBound: SInt) => {
    clipOpSS((a: SInt, b: SInt) => a -& b, x, y, upperBound, lowerBound)
  }

  val clipAddSign = (x: SInt, y: SInt) => {
    val width = x.getWidth
    val upperBound = getMaxSInt(width)
    val lowerBound = getMaxSInt(width)
    clipAdd(x, y, upperBound, lowerBound)
  }
  val clipAddUnsign = (x: UInt, y: UInt) => {
    val width = x.getWidth
    val upperBound = getMaxUInt(width)
    clipAdd(x.zext, y.zext, upperBound.zext, 0.S)(width - 1, 0).asUInt
  }
  val clipAddUnsignSign = (x: UInt, y: SInt) => {
    val width = x.getWidth
    val upperBound = getMaxUInt(width).zext
    clipAdd(x.zext, y, upperBound, 0.S)(width - 1, 0).asUInt
  }

}
