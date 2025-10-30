package rvtng.vec

import chisel3._
import chisel3.util._

case class VECParams(
                    maxLatency: Int = 16
                    ) {
  val log2MaxLatency = log2Ceil(maxLatency)
}

class VecTop {

}
