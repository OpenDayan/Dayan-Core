package fpexputils

import chisel3._
import chisel3.util._

/** Chisel translation of the VHDL entity OutputIEEE_8_23_to_8_23_Freq150_uid2.
  *
  * This module converts a 34-bit internal floating-point format back to the 32-bit IEEE 754 standard. The logic is
  * purely combinational.
  *
  * Input Format (34-bit): exn(2) | sign(1) | exponent(8) | fraction(23)
  */
class OutputIEEE extends Module {
    val io = IO(new Bundle {
        val X = Input(UInt(34.W))
        val R = Output(UInt(32.W))
    })

    // 1. Deconstruct the 34-bit input into its fields.
    val exnX  = io.X(33, 32)
    val sX_in = io.X(31)
    val expX  = io.X(30, 23)
    val fracX = io.X(22, 0)

    // 2. Define boolean flags for each exception case for clarity.
    val isZero     = (exnX === "b00".U)
    val isNormal   = (exnX === "b01".U)
    val isInfinity = (exnX === "b10".U)
    val isNaN      = (exnX === "b11".U)

    // A helper flag for subnormal numbers (normal classification with zero exponent).
    val isSubnormal = isNormal && (expX === 0.U)

    // 3. Determine the final sign bit (sX).
    // The sign is passed through for all cases except NaN, where it is forced to 0.
    val sX = Mux(isNaN, 0.U(1.W), sX_in)

    // 4. Determine the final 8-bit exponent (expR).
    val expR = MuxCase(
      expX,
      Seq(
        isZero                -> 0.U(8.W),
        (isInfinity || isNaN) -> "hFF".U(8.W) // All ones for infinity or NaN
      )
    )

    // 5. Determine the final 23-bit fraction (fracR).
    val fracR = MuxCase(
      fracX,
      Seq(
        isZero      -> 0.U(23.W),
        isSubnormal -> Cat(1.U(1.W), fracX(22, 1)), // Custom subnormal re-packing
        isInfinity  -> 0.U(23.W),
        isNaN       -> Cat(0.U(22.W), 1.U(1.W))     // Create a canonical quiet NaN
      )
    )

    // 6. Assemble the final 32-bit IEEE 754 output vector.
    io.R := Cat(sX, expR, fracR)
}
