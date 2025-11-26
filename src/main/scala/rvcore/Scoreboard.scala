package rvcore

import chisel3._
import chisel3.util._

class Scoreboard(numRegs: Int = 32) extends Module {
  val io = IO(new Bundle {
    val src1 = Input(UInt(5.W))
    val src2 = Input(UInt(5.W))
    val dest = Input(UInt(5.W))
    val setBusy = Input(Bool()) // Asserted when issuing an instruction that writes to dest
    
    // Clear signals from different writeback sources
    val clearALU = Input(Valid(UInt(5.W)))
    val clearMem = Input(Valid(UInt(5.W)))
    val clearNPU = Input(Valid(UInt(5.W)))

    val stall = Output(Bool()) // Stall decode if RAW or WAW hazard
  })

  // Busy table: 1 bit per register
  val busy = RegInit(VecInit(Seq.fill(numRegs)(false.B)))

  // Logic to check hazards
  // x0 is never busy
  val src1Busy = busy(io.src1) && io.src1 =/= 0.U
  val src2Busy = busy(io.src2) && io.src2 =/= 0.U
  val destBusy = busy(io.dest) && io.dest =/= 0.U // WAW Hazard protection

  // RAW or WAW causes stall
  io.stall := src1Busy || src2Busy || (destBusy && io.setBusy)

  // Update Busy Bits
  when(io.setBusy && io.dest =/= 0.U && !io.stall) {
    busy(io.dest) := true.B
  }

  // Clear Busy Bits (Priority: Last write wins in logic, but physically shouldn't happen simultaneously for same reg if ID manages it well, 
  // but simpler to just clear. Note: In OoO WB, we need to be careful about clearing. 
  // If we have multiple pending writes to same reg (impossible with current WAW check), we are safe.
  // Since we block Issue on WAW, there is only ever one instruction writing to a register at a time.)
  
  when(io.clearALU.valid && io.clearALU.bits =/= 0.U) {
    busy(io.clearALU.bits) := false.B
  }
  when(io.clearMem.valid && io.clearMem.bits =/= 0.U) {
    busy(io.clearMem.bits) := false.B
  }
  when(io.clearNPU.valid && io.clearNPU.bits =/= 0.U) {
    busy(io.clearNPU.bits) := false.B
  }
}

