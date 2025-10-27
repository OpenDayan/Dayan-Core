package RVTNG.vec

import chisel3._
import chisel3.util.log2Ceil

object VecConfiguration {
  val _GLOBAL_INST_LENGTH = 32
  val _GLOBAL_GPR_WIDTH   = 64
  val _GLOBAL_UARM_LOG2   = 20

  val _VEC_IQ_ENTRY = 64
  val _VEC_IQ_ENTRY_LOG2 = log2Ceil(_VEC_IQ_ENTRY)

  val _VEC_RF_ENTRY = 32
  val _VEC_RF_ENTRY_LOG2 = log2Ceil(_VEC_RF_ENTRY)

  val _VEC_RF_BANK = 2
  val _VEC_EXEC_PART = 4

  val _VEC_STALL_CYC = 32
  val _VEC_STALL_CYC_LOG2 = log2Ceil(_VEC_STALL_CYC)
}

class InstT extends Bundle {
  val inst  = UInt(VecConfiguration._GLOBAL_INST_LENGTH.W)
  val rs1   = UInt(VecConfiguration._GLOBAL_GPR_WIDTH.W)
  val rs2   = UInt(VecConfiguration._GLOBAL_GPR_WIDTH.W)
  val rd    = UInt(VecConfiguration._GLOBAL_GPR_WIDTH.W)
  val block = Bool()
}

class VecInstT extends InstT {
  val stall_cyc = UInt(VecConfiguration._VEC_STALL_CYC_LOG2.W)
}

class VecInstExecT extends InstT {
  val r1v = Bool()
  val r2v = Bool()
  val r1 = UInt(VecConfiguration._VEC_RF_ENTRY_LOG2.W)
  val r2 = UInt(VecConfiguration._VEC_RF_ENTRY_LOG2.W)
}

class VecInstDecT extends Bundle {
  val r1 = UInt(VecConfiguration._VEC_RF_ENTRY_LOG2.W)
  val r2 = UInt(VecConfiguration._VEC_RF_ENTRY_LOG2.W)
  val r1v = Bool()
  val r2v = Bool()
  val latency = UInt(VecConfiguration._VEC_STALL_CYC_LOG2.W)
}

class VecSchedulerInfoT extends Bundle {
  val rs1_bankready = UInt(VecConfiguration._VEC_RF_BANK.W)
  val rs2_bankready = UInt(VecConfiguration._VEC_RF_BANK.W)
  val exec_ready = UInt(VecConfiguration._VEC_EXEC_PART.W)
}

class VecTile extends Bundle {
  val saddr = UInt(VecConfiguration._GLOBAL_UARM_LOG2.W)
  val vaddr = UInt(VecConfiguration._GLOBAL_UARM_LOG2.W)
}
