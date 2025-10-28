package rvtng.top

import chisel3._

class TensorInst extends Bundle {
  val instWord = UInt(32.W)
  val rs1 = UInt(64.W) // Based Address of A
  val rs2 = UInt(64.W) // Based Address of B
  val rd  = UInt(64.W)
}

class TensorResp extends Bundle {
  val data = UInt(64.W)
  val rd   = UInt(5.W)
}
