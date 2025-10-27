package top

import chisel3._

class TensorInst extends Bundle {
  val instWord = UInt(32.W)
  val src0 = UInt(64.W) // Based Address of A
  val src1 = UInt(64.W) // Based Address of B
  val dst  = UInt(64.W) // Based Address of C
}
