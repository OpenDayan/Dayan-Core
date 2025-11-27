package rvtng.neomat

import chisel3._
import chisel3.util._
import hardfloat._

case class KulischAccConfig(
  inExpWidth: Int,
  inSigWidth: Int,
  outExpWidth: Int,
  outSigWidth: Int,
  numInputs: Int,
  // Optional manual override for accumulator width
  accWidth: Option[Int] = None
)

class KulischAccumulator(c: KulischAccConfig) extends Module {
  val io = IO(new Bundle {
    // Input and Output are now RawFloat
    val in = Input(Vec(c.numInputs, new RawFloat(c.inExpWidth, c.inSigWidth)))
    val out = Output(new RawFloat(c.outExpWidth, c.outSigWidth))
  })

  // --------------------------------------------------------------------------
  // 1. Inputs
  // --------------------------------------------------------------------------
  val rawInputs = io.in

  // --------------------------------------------------------------------------
  // 2. Configure Accumulator Range
  // --------------------------------------------------------------------------
  // Calculate dynamic range required.
  val maxExp = BigInt(1) << (c.inExpWidth - 1)
  val minExp = -(BigInt(1) << (c.inExpWidth - 1)) - BigInt(c.inSigWidth) // ample guard
  
  // Ensure sufficient width to hold the smallest denormal to largest normal
  // Plus log2(numInputs) to handle sum overflow
  val sumGuardBits = log2Ceil(c.numInputs + 1)
  val defaultAccWidth = (maxExp - minExp).toInt + 64 + sumGuardBits
  val accWidth = c.accWidth.getOrElse(defaultAccWidth)
  val bias = -minExp

  // --------------------------------------------------------------------------
  // 3. Align and Sum Inputs (Combinational)
  // --------------------------------------------------------------------------
  // Value = raw.sig * 2^(sExp - (sigWidth - 1))
  // Aligned Value (integer) = raw.sig << (sExp + bias - (sigWidth - 1))
  
  val shiftedInputs = rawInputs.map { raw =>
    // Determine shift amount
    // Shift = sExp + bias - (inSigWidth - 1)
    // raw.sExp is SInt. bias is BigInt.
    
    val shiftAmtSInt = raw.sExp +& (bias - (c.inSigWidth - 1)).S
    val shiftAmt = shiftAmtSInt.asUInt 

    val sigSInt = Wire(SInt((c.inSigWidth + 2).W))
    // Note: RawFloat(E, S).sig has width S+1.
    // For valid inputs, MSB (index S) is 0. S-1 is the leading 1 (or 0 for denorm).
    // We can just extend it.
    sigSInt := Mux(raw.sign, -raw.sig.zext, raw.sig.zext)
    
    // Shift
    val shifted = (sigSInt << shiftAmt)(accWidth - 1, 0).asSInt
    
    Mux(raw.isZero || raw.isNaN || raw.isInf, 0.S, shifted)
  }

  // Tree Adder
  val sumInputs = shiftedInputs.reduce(_ +& _)

  // --------------------------------------------------------------------------
  // 4. Normalization and Output Generation
  // --------------------------------------------------------------------------
  // Logic is now purely combinational based on sumInputs
  
  val accSign = sumInputs(accWidth - 1)
  val accAbs = Mux(accSign, -sumInputs, sumInputs).asUInt

  // Find MSB position (Leading Zeros)
  val lzCount = PriorityEncoder(Reverse(accAbs))
  val msbIndex = (accWidth - 1).U - lzCount
  
  val isZero = accAbs === 0.U

  // Calculate Exponent
  // sExp = msbIndex + minExp
  val rawSExp = msbIndex.asSInt +& minExp.S

  // Normalize Accumulator to extract significand
  val normAcc = (accAbs << lzCount)(accWidth - 1, 0)
  
  // Use an intermediate precision for rounding
  val interSigWidth = c.outSigWidth + 6
  val interExpWidth = c.outExpWidth + 16 
  
  // Extract top bits. 
  val topBits = normAcc(accWidth - 1, accWidth - interSigWidth)
  
  // Calculate sticky bit from the remaining lower bits
  val sticky = normAcc(accWidth - interSigWidth - 1, 0).orR
  
  // Construct RawFloat for the rounder
  val rawSig = Wire(UInt((interSigWidth + 1).W))
  rawSig := 0.U(1.W) ## (topBits(interSigWidth - 1, 1) ## (topBits(0) | sticky))

  val interRaw = Wire(new RawFloat(interExpWidth, interSigWidth))
  interRaw.isNaN := false.B 
  interRaw.isInf := false.B
  interRaw.isZero := isZero
  interRaw.sign := accSign
  interRaw.sExp := rawSExp
  interRaw.sig := rawSig

  // Use RoundAnyRawFNToRecFN to perform correct rounding
  val rounder = Module(new RoundAnyRawFNToRecFN(
    interExpWidth, interSigWidth,
    c.outExpWidth, c.outSigWidth,
    options = 0
  ))

  rounder.io.invalidExc := false.B
  rounder.io.infiniteExc := false.B
  rounder.io.in := interRaw
  rounder.io.roundingMode := consts.round_near_even 
  rounder.io.detectTininess := consts.tininess_afterRounding
  
  // Convert RecFN output back to RawFloat
  io.out := rawFloatFromRecFN(c.outExpWidth, c.outSigWidth, rounder.io.out)

}
