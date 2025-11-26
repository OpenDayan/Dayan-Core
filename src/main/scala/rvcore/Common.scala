package rvcore

import chisel3._
import chisel3.util._

object RISCVConstants {
  // RISC-V 64-bit Instructions
  val OP_LOAD   = 0x03.U(7.W)
  val OP_STORE  = 0x23.U(7.W)
  val OP_MADD   = 0x43.U(7.W)
  val OP_BRANCH = 0x63.U(7.W)
  val OP_JALR   = 0x67.U(7.W)
  val OP_JAL    = 0x6F.U(7.W)
  val OP_OP_IMM = 0x13.U(7.W)
  val OP_OP     = 0x33.U(7.W)
  val OP_SYSTEM = 0x73.U(7.W)
  val OP_AUIPC  = 0x17.U(7.W)
  val OP_LUI    = 0x37.U(7.W)
  val OP_OP_IMM_32 = 0x1B.U(7.W)
  val OP_OP_32     = 0x3B.U(7.W)
  // Custom NPU Opcode (using a custom-0 or similar space)
  // Assuming NPU instructions are mapped to CUSTOM-0 (0x0B) or similar.
  // Let's check standard custom opcodes: custom-0=0x0b, custom-1=0x2b
  val OP_CUSTOM_0 = 0x0B.U(7.W) 

  // ALU Operations
  val ALU_ADD  = 0.U(4.W)
  val ALU_SUB  = 1.U(4.W)
  val ALU_SLL  = 2.U(4.W)
  val ALU_SLT  = 3.U(4.W)
  val ALU_SLTU = 4.U(4.W)
  val ALU_XOR  = 5.U(4.W)
  val ALU_SRL  = 6.U(4.W)
  val ALU_SRA  = 7.U(4.W)
  val ALU_OR   = 8.U(4.W)
  val ALU_AND  = 9.U(4.W)
  val ALU_COPY_A = 10.U(4.W)
  val ALU_COPY_B = 11.U(4.W)
}

// Control Signals
class CtrlSignals extends Bundle {
  val useALU  = Bool()
  val useMem  = Bool()
  val useNPU  = Bool()
  val memRead = Bool()
  val memWrite= Bool()
  val regWrite= Bool()
  val branch  = Bool()
  val jump    = Bool()
  val aluOp   = UInt(4.W)
  val opType  = UInt(2.W) // 0: R, 1: I, 2: S/B, 3: U/J
}

// Pipeline Registers
class IF_ID extends Bundle {
  val pc   = UInt(64.W)
  val inst = UInt(32.W)
}

class ID_EX extends Bundle {
  val pc      = UInt(64.W)
  val inst    = UInt(32.W)
  val rs1Data = UInt(64.W)
  val rs2Data = UInt(64.W)
  val imm     = UInt(64.W)
  val rd      = UInt(5.W)
  val ctrl    = new CtrlSignals
}

class EX_WB extends Bundle {
  val pc      = UInt(64.W)
  val rd      = UInt(5.W)
  val data    = UInt(64.W)
  val regWrite= Bool()
}

