package rvtng.neomat

import chisel3._
import hardfloat._
import _root_.circt.stage.ChiselStage

class KulischAccumulatorTestTop extends Module {
  // Test configuration: 4-input FP32 Accumulator
  val io = IO(new Bundle {
    val in = Input(Vec(4, UInt(32.W))) // IEEE 754 Single Precision
    val out = Output(UInt(32.W))
  })

  val expWidth = 8
  val sigWidth = 24

  // 1. Convert IEEE FP32 Inputs to RawFloat using the helper object
  // This is much cleaner than RecFN intermediate conversion
  val rawInputs = io.in.map { f32 =>
    rawFloatFromFN(expWidth, sigWidth, f32)
  }

  // 2. Instantiate the Kulisch Accumulator
  val kConfig = KulischAccConfig(
    inExpWidth = expWidth,
    inSigWidth = sigWidth,
    outExpWidth = expWidth,
    outSigWidth = sigWidth,
    numInputs = 4,
    accWidth = Some(32)
  )
  
  val dut = Module(new KulischAccumulator(kConfig))
  
  // Connect inputs
  dut.io.in.zip(rawInputs).foreach { case (port, signal) => 
    port := signal 
  }

  // 3. Convert Output RawFloat back to IEEE FP32
  // We convert RawFloat -> RecFN -> IEEE FN because HardFloat lacks a direct RawFloat->FN rounder.
  // The RoundRawFNToRecFN module handles the packing logic.
  
  val finalRecGen = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
  
  // Connect fields, padding sig for RoundRawFNToRecFN (which expects sigWidth + 2 input width)
  // DUT output sig is (sigWidth + 1) wide.
  finalRecGen.io.in.isNaN  := dut.io.out.isNaN
  finalRecGen.io.in.isInf  := dut.io.out.isInf
  finalRecGen.io.in.isZero := dut.io.out.isZero
  finalRecGen.io.in.sign   := dut.io.out.sign
  finalRecGen.io.in.sExp   := dut.io.out.sExp
  finalRecGen.io.in.sig    := dut.io.out.sig ## 0.U(1.W)
  
  finalRecGen.io.invalidExc := false.B
  finalRecGen.io.infiniteExc := false.B
  finalRecGen.io.roundingMode := consts.round_near_even
  finalRecGen.io.detectTininess := consts.tininess_afterRounding

  // RecFN -> IEEE FN
  io.out := fNFromRecFN(expWidth, sigWidth, finalRecGen.io.out)

}


object KulischAccumulatorTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new KulischAccumulatorTestTop(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/KulischAccumulatorTestTop"
    )
  )
}

