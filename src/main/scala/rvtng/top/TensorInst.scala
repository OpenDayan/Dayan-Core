package rvtng.top

import chisel3._

class TensorInst extends Bundle {
  val instWord = UInt(64.W)
  val rs1Data = UInt(64.W) // Based Address of A
  val rs2Data = UInt(64.W) // Based Address of B
  val rdData  = UInt(64.W)
}

class TensorResp extends Bundle {
  val data = UInt(64.W)
  val rd   = UInt(5.W)
}

class ScoreboardComp extends Bundle {
  val entryId = UInt(64.W)
}

