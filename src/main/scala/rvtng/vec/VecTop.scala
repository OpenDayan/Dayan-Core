package rvtng.vec

import chisel3._
import chisel3.util._

case class VECParams(
                    maxInstCount: Int = 512,
                    maxLatency: Int = 16
                    ) {
  val log2MaxInstCount = log2Ceil(maxInstCount)
  val log2MaxLatency = log2Ceil(maxLatency)
}

class VecTop {

}
