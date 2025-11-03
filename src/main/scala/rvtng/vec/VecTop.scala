package rvtng.vec

import chisel3._
import chisel3.util._

case class VecParams(
                    maxInstCount: Int = 512,
                    maxThreadCount: Int = 32, // 最多同时 32 个并行线程
                    maxLatency: Int = 16,
                    tcsrBanks: Int = 8
                    ) {
  val log2MaxInstCount = log2Ceil(maxInstCount)
  val log2MaxLatency = log2Ceil(maxLatency)
  val log2MaxThreadCount = log2Ceil(maxThreadCount)
}

class VecTop {

}
