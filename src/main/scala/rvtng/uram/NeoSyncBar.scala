package rvtng.uram

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import rvtng.utils._

class NeoSyncBarChannel[TS <: Data, TD <: Data](channelWidth: Int, genS: TS, genD: TD) extends Module {
  val io = IO(new Bundle{
    val srcStreamI = Flipped(Vec(channelWidth, Decoupled(genS)))
    val srcStreamO = Vec(channelWidth, Decoupled(genS))
    val dstStreamI = Flipped(Vec(channelWidth, Decoupled(genD)))
    val dstStreamO = Vec(channelWidth, Decoupled(genD))
  })
}

class NeoSyncBar[TS <: Data, TD <: Data](channelWidth: Int, channelCount: Int, genS: TS, genD: TD) extends Module {
  val io = IO(new Bundle{
    val srcStreamI = Flipped(Vec(channelWidth * channelCount, Decoupled(genS)))
    val srcStreamO = Vec(channelWidth * channelCount, Decoupled(genS))
    val dstStreamI = Flipped(Vec(channelWidth * channelCount, Decoupled(genD)))
    val dstStreamO = Vec(channelWidth * channelCount, Decoupled(genD))
  })
}
