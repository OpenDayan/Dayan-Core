package fpexputils

import chisel3._
import chisel3.util._

/** Chisel translation of the VHDL entity InputIEEE_8_23_to_8_23_Freq150_uid2.
  *
  * This module converts a 32-bit standard IEEE 754 float into a 34-bit internal format by adding a 2-bit exception
  * field (exn) at the MSB. The logic is entirely combinational.
  *
  * Output Format: exn(2) | sign(1) | exponent(8) | fraction(23)
  */
class InputIEEE extends Module {
    val io = IO(new Bundle {
        val X = Input(UInt(32.W))
        val R = Output(UInt(34.W))
    })

    // 1. Parse the input IEEE 754 fields.
    val sX    = io.X(31)
    val expX  = io.X(30, 23)
    val fracX = io.X(22, 0)

    // 2. Detect special exponent/fraction cases.
    val expIsZero  = (expX === 0.U)
    val expIsMax   = expX.andR // True if all bits are 1
    val fracIsZero = (fracX === 0.U)

    // 3. Handle a custom subnormal representation.
    // The comment suggests that if exp=0 and frac's MSB=1, it's a special subnormal.
    val isCustomSubnormal = expIsZero && fracX(22).asBool

    // If it's a custom subnormal, shift the fraction left by one. Otherwise, keep it.
    val fracR = Mux(isCustomSubnormal, Cat(fracX(21, 0), 0.U(1.W)), fracX)

    // The exponent is passed through unchanged.
    val expR = expX

    // 4. Classify the number type based on the fields.
    val isZero     = expIsZero && !fracX(22).asBool // VHDL: zero <= expZero and not reprSubNormal;
    val isInfinity = expIsMax && fracIsZero
    val isNaN      = expIsMax && !fracIsZero

    // 5. Generate the 2-bit exception code (exn).
    val exnR = MuxCase(
      "b01".U(2.W),
      Seq(
        isZero     -> "b00".U,
        isInfinity -> "b10".U,
        isNaN      -> "b11".U
      )
    )

    // 6. Assemble the final 34-bit output vector.
    // R <= exnR & sX & expR & fracR;
    io.R := Cat(exnR, sX, expR, fracR)
}
