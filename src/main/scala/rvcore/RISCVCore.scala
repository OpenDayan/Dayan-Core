package rvcore

import chisel3._
import chisel3.util._
import rvtng.top.{TensorInst, TensorResp}

class RISCVCore(resetVector: BigInt = 0x80000000L) extends Module {
  val io = IO(new Bundle {
    // External Memory Interface (TileLink)
    val icacheTL = new TLBundle(TLParams())
    val dcacheTL = new TLBundle(TLParams())
    
    // NPU Interface
    val npuReq  = Decoupled(new TensorInst)
    val npuResp = Flipped(Decoupled(new TensorResp))
    
    // Debug / Status
    val error = Output(Bool())
  })

  // --- Parameters ---
  val xlen = 64
  
  // --- Components ---
  val fetch = Module(new Fetch(resetVector))
  val icache = Module(new NonBlockingCache(CacheParams(isICache = true.B), TLParams()))
  val dcache = Module(new NonBlockingCache(CacheParams(isICache = false.B), TLParams()))
  val scoreboard = Module(new Scoreboard)
  
  // Register File
  val regFile = RegInit(VecInit(Seq.fill(32)(0.U(xlen.W))))
  
  // --- Pipeline Signals ---
  
  // 1. Fetch Stage
  fetch.io.imem.req <> icache.io.cpu.req
  fetch.io.imem.resp <> icache.io.cpu.resp
  io.icacheTL <> icache.io.tl

  // 2. Decode / Execute Stage
  val if_id = fetch.io.out
  val inst = if_id.bits.inst
  val pc   = if_id.bits.pc
  
  // Decoder
  val opcode = inst(6, 0)
  val rd     = inst(11, 7)
  val funct3 = inst(14, 12)
  val rs1    = inst(19, 15)
  val rs2    = inst(24, 20)
  val funct7 = inst(31, 25)
  val immI   = Cat(Fill(52, inst(31)), inst(31, 20)).asSInt.asUInt
  val immS   = Cat(Fill(52, inst(31)), inst(31, 25), inst(11, 7)).asSInt.asUInt
  val immB   = Cat(Fill(51, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt.asUInt
  val immU   = Cat(Fill(32, inst(31)), inst(31, 12), 0.U(12.W)).asSInt.asUInt
  val immJ   = Cat(Fill(43, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)).asSInt.asUInt
  
  // Operand Read
  val rs1Data = Mux(rs1 === 0.U, 0.U, regFile(rs1))
  val rs2Data = Mux(rs2 === 0.U, 0.U, regFile(rs2))

  // Scoreboard Check
  scoreboard.io.src1 := rs1
  scoreboard.io.src2 := rs2
  scoreboard.io.dest := rd
  
  val isLoad = opcode === RISCVConstants.OP_LOAD
  val isNPU  = opcode === RISCVConstants.OP_CUSTOM_0
  val isALU  = opcode === RISCVConstants.OP_OP || opcode === RISCVConstants.OP_OP_IMM || 
               opcode === RISCVConstants.OP_LUI || opcode === RISCVConstants.OP_AUIPC
  val writesRd = (isLoad || isNPU || isALU) && rd =/= 0.U
  
  // Cutting combinational loop:
  // The scoreboard stall signal depends on setBusy if destBusy is set.
  // setBusy depends on if_id.valid && !stall.
  // This creates a loop: stall -> setBusy -> stall.
  //
  // Fix: scoreboard.io.setBusy should NOT depend on stall. 
  // The scoreboard logic: "update busy bits" implies the instruction IS ISSUED.
  // If we stall, we don't issue, so we shouldn't update busy bits.
  // However, the `io.stall` output logic includes `destBusy && io.setBusy` (WAW check).
  // If `setBusy` depends on `!stall`, we have a loop.
  //
  // Solution: Separate "Check Hazard" (read only) from "Update Busy" (write).
  // But my Scoreboard module combines them. 
  // Let's just pass `writesRd && if_id.valid` to scoreboard as `setBusy`,
  // BUT inside scoreboard, only trigger WAW check if `setBusy` is true, 
  // AND `io.stall` output logic does NOT feed back into `setBusy`.
  //
  // WAIT: If `io.stall` is high, `if_id.ready` is low, so we don't consume the instruction.
  // So we MUST NOT update the busy table if we stall.
  // The loop is:
  //   stall = ... || (destBusy && setBusy)
  //   setBusy = ... && !stall
  //
  // Breaking the loop:
  // WAW check (destBusy) is a reason to stall. It doesn't need `setBusy` to be true to be a hazard.
  // If `destBusy` is true, and we WANT to write to dest (writesRd && if_id.valid), then we stall.
  // So `stall` calculation should use `writesRd && if_id.valid` directly, not the `setBusy` input which is gated by stall.
  // 
  // Let's look at Scoreboard.scala again (I can't edit it here easily without rewriting it, but I can change how I connect it).
  // Actually, I can change how I connect to it.
  // The `setBusy` input of Scoreboard is used for TWO things:
  // 1. WAW Check: `io.stall := ... || (destBusy && io.setBusy)`
  // 2. State Update: `when(io.setBusy ...)`
  // 
  // If I change Scoreboard to just take `wantsToWrite` instead of `setBusy` for WAW check? 
  // Or simpler: In RISCVCore, calculate `stall` WITHOUT `scoreboard.io.stall`'s WAW part? No, that logic is inside.
  //
  // Correct fix in RISCVCore:
  // Pass the "intent" to write to Scoreboard, let Scoreboard decide stall.
  // But Scoreboard updates state based on `setBusy`. If we stall, we must NOT update state.
  //
  // My Scoreboard implementation:
  //   io.stall := src1Busy || src2Busy || (destBusy && io.setBusy)
  //   when(io.setBusy && io.dest =/= 0.U && !io.stall) { busy(io.dest) := true.B }
  //
  // If I drive `io.setBusy := writesRd && if_id.valid`, then:
  //   stall = src1Busy || src2Busy || (destBusy && (writesRd && if_id.valid))
  //   busy_update = (writesRd && if_id.valid) && !stall
  // This breaks the loop! The loop was `io.setBusy := ... && !scoreboard.io.stall`.
  // By removing `!scoreboard.io.stall` from `io.setBusy` assignment, we fix the combinational loop.
  // The `busy_update` inside Scoreboard already checks `!io.stall`, so it's safe.
  
  scoreboard.io.setBusy := writesRd && if_id.valid // Removed && !stall
  
  // Stall Logic
  // We stall if Scoreboard says hazard, OR if D-Cache not ready for load/store, OR NPU not ready
  val stall = scoreboard.io.stall || 
              ((isLoad || opcode === RISCVConstants.OP_STORE) && !dcache.io.cpu.req.ready) || 
              (isNPU && !io.npuReq.ready)
              
  fetch.io.stall := stall
  if_id.ready := !stall

  // Execution Logic
  val op1 = rs1Data
  val op2 = Mux(opcode === RISCVConstants.OP_OP_IMM, immI, rs2Data)
  val aluResult = Wire(UInt(64.W))
  
  aluResult := 0.U
  switch(opcode) {
    is(RISCVConstants.OP_OP_IMM) {
        switch(funct3) {
            is(0.U) { aluResult := op1 + op2 } // ADDI
            is(4.U) { aluResult := op1 ^ op2 } // XORI
            is(6.U) { aluResult := op1 | op2 } // ORI
            is(7.U) { aluResult := op1 & op2 } // ANDI
        }
    }
    is(RISCVConstants.OP_OP) {
        switch(funct3) {
            is(0.U) { aluResult := Mux(funct7(5), op1 - op2, op1 + op2) } // SUB/ADD
        }
    }
    is(RISCVConstants.OP_LUI)   { aluResult := immU }
    is(RISCVConstants.OP_AUIPC) { aluResult := pc + immU }
  }

  // Branch Unit
  val branchTaken = Wire(Bool())
  branchTaken := false.B
  when (opcode === RISCVConstants.OP_BRANCH) {
      switch(funct3) {
          is(0.U) { branchTaken := rs1Data === rs2Data } // BEQ
          is(1.U) { branchTaken := rs1Data =/= rs2Data } // BNE
      }
  }
  val jumpTarget = Mux(opcode === RISCVConstants.OP_JALR, (rs1Data + immI) & (~1.U(64.W)), pc + immJ)
  val isJump = opcode === RISCVConstants.OP_JAL || opcode === RISCVConstants.OP_JALR
  
  fetch.io.jump := (branchTaken || isJump) && if_id.valid && !stall
  fetch.io.jumpAddr := Mux(branchTaken, pc + immB, jumpTarget)

  // --- Dispatch / Issue ---
  
  // NPU Dispatch
  io.npuReq.valid := if_id.valid && isNPU && !stall
  io.npuReq.bits.instWord := inst
  io.npuReq.bits.rs1Data := rs1Data
  io.npuReq.bits.rs2Data := rs2Data
  io.npuReq.bits.rdData := 0.U
  
  // Load/Store Dispatch
  dcache.io.cpu.req.valid := if_id.valid && (isLoad || opcode === RISCVConstants.OP_STORE) && !stall
  dcache.io.cpu.req.bits.addr := rs1Data + Mux(opcode === RISCVConstants.OP_STORE, immS, immI)
  dcache.io.cpu.req.bits.data := rs2Data
  dcache.io.cpu.req.bits.cmd := Mux(opcode === RISCVConstants.OP_STORE, 1.U, 0.U)
  dcache.io.cpu.req.bits.mask := "hFF".U 
  dcache.io.cpu.req.bits.id := rd

  io.dcacheTL <> dcache.io.tl

  // --- Writeback Stage (OoO Handling) ---
  
  // 1. ALU Writeback
  scoreboard.io.clearALU.valid := if_id.valid && isALU && !stall && rd =/= 0.U
  scoreboard.io.clearALU.bits := rd
  
  when (if_id.valid && isALU && !stall && rd =/= 0.U) {
      regFile(rd) := aluResult
  }
  
  // 2. Memory Writeback (Asynchronous)
  dcache.io.cpu.resp.ready := true.B
  scoreboard.io.clearMem.valid := dcache.io.cpu.resp.valid
  scoreboard.io.clearMem.bits := dcache.io.cpu.resp.bits.id
  
  when (dcache.io.cpu.resp.valid && dcache.io.cpu.resp.bits.id =/= 0.U) {
      regFile(dcache.io.cpu.resp.bits.id) := dcache.io.cpu.resp.bits.data
  }
  
  // 3. NPU Writeback
  io.npuResp.ready := true.B
  scoreboard.io.clearNPU.valid := io.npuResp.valid
  scoreboard.io.clearNPU.bits := io.npuResp.bits.rd
  
  when (io.npuResp.valid && io.npuResp.bits.rd =/= 0.U) {
      regFile(io.npuResp.bits.rd) := io.npuResp.bits.data
  }
  
  io.error := false.B 
}
