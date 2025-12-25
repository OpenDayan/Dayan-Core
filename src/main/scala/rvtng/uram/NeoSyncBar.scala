package rvtng.uram

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import rvtng.utils._

class NeoSyncBarChannel[TS <: Data, TD <: Data](channelWidth: Int, fifoSize: Int, genS: TS, genD: TD) extends Module {
  val io = IO(new Bundle{
    val srcStreamI = Flipped(Vec(channelWidth, Decoupled(genS)))
    val srcStreamO = Vec(channelWidth, Decoupled(genS))
    val dstStreamI = Flipped(Vec(channelWidth, Decoupled(genD)))
    val dstStreamO = Vec(channelWidth, Decoupled(genD))
  })

  // Credit
  private val credit = RegInit(fifoSize.U)
  private val creditEmpty = credit === 0.U
  private val creditSub = Wire(Bool())
  creditSub := 0.B
  private val creditAdd = Wire(Bool())
  creditAdd := 0.B
  credit := credit + creditAdd.asUInt - creditSub.asUInt

  // 每一个 channel 包含多组 Stream，每个 Stream 在出口处包含一个尺寸为 fifoSize 的队列。
  // 队列每一次的出队操作，将帮助入口处的 Credit Counter + 1
  // 一个 Channel 的出口，必须全部就绪的时候，才能出队
  // 源数据打包为单个 DecoupledIO
  class PackedChannel extends Bundle {
    val vld = Bool()
    val pld = genS
  }
  private val srcChannelStream = Wire(DecoupledIO(Vec(channelWidth, new PackedChannel)))
  srcChannelStream.bits.zip(io.srcStreamI).foreach(iter => {
    val (l, r) = iter
    l.pld := r.bits
    l.vld := r.valid
    r.ready := srcChannelStream.ready
  })
  private val srcQ = Queue(srcChannelStream, 2, false, false)
  private val srcIssuedVec = RegInit(VecInit.tabulate(channelWidth)(i => 0.B))
  io.srcStreamO.zip(srcIssuedVec).zip(srcQ.bits).foreach(iter => {
    val ((l, issued), r) = iter
    l.valid := (r.vld & srcQ.valid) & !issued & !creditEmpty
    l.bits := r.pld
  })

  // Update srcIssuedVec
  private val nxtSrcIssuedVec = VecInit(srcIssuedVec.zip(io.srcStreamO).map( iter => {
    val (l, r) = iter
    l | r.ready
  }))
  when(srcQ.fire) {
    srcIssuedVec := VecInit.tabulate(channelWidth)(i => 0.B)
  }.elsewhen(!creditEmpty) {
    srcIssuedVec := nxtSrcIssuedVec
  }
  srcQ.ready := nxtSrcIssuedVec.reduceTree(_ & _) & !creditEmpty

  creditSub := srcQ.fire

  // 内部队列：记录每轮请求的 vld 信息
  private val srcVldStream = Wire(DecoupledIO(Vec(channelWidth, Bool())))
  srcVldStream.valid := srcQ.fire
  srcVldStream.bits := VecInit(srcQ.bits.map(_.vld))
  chisel3.assert(!(srcVldStream.valid & !srcVldStream.ready))
  private val srcVldQ = Queue(srcVldStream, fifoSize, false, false)

  // 输出处理，每个 Stream 都各自有一个队列接收输入
  private val dstQ = VecInit(io.dstStreamI.map(Queue(_, fifoSize, false, false)))

  // 根据 srcVldQ 中的情况，置位 dstStreamO
  io.dstStreamO.zip(srcVldQ.bits).zip(dstQ).foreach(iter => {
    val ((l, vld), r) = iter
    l.valid := vld & r.valid & srcVldQ.ready
    r.ready := vld & l.ready & srcVldQ.ready
    l.bits := r.bits
  })
  srcVldQ.ready := VecInit(srcVldQ.bits.zip(dstQ).map(iter => {
    val (vld, r) = iter
    r.valid | !vld
  })).reduceTree(_ & _)
  creditAdd := srcVldQ.fire
}

class NeoSyncBar[TS <: Data, TD <: Data](channelWidth: Int, channelCount: Int, fifoSize: Int, genS: TS, genD: TD) extends Module {
  val io = IO(new Bundle{
    val srcStreamI = Flipped(Vec(channelWidth * channelCount, Decoupled(genS)))
    val srcStreamO = Vec(channelWidth * channelCount, Decoupled(genS))
    val dstStreamI = Flipped(Vec(channelWidth * channelCount, Decoupled(genD)))
    val dstStreamO = Vec(channelWidth * channelCount, Decoupled(genD))
  })
  for(channel <- 0 until channelCount) {
    val channelSyncBar = Module(new NeoSyncBarChannel(channelWidth, fifoSize, genS, genD)).io
    for(p <- 0 until channelWidth) {
      channelSyncBar.srcStreamI(p) <> io.srcStreamI(channel * channelWidth + p)
      channelSyncBar.srcStreamO(p) <> io.srcStreamO(channel * channelWidth + p)
      channelSyncBar.dstStreamI(p) <> io.dstStreamI(channel * channelWidth + p)
      channelSyncBar.dstStreamO(p) <> io.dstStreamO(channel * channelWidth + p)
    }
  }
}

object NeoSyncBarChannel extends App {
  ChiselStage.emitSystemVerilog(
    new NeoSyncBarChannel(4, 16, UInt(32.W), UInt(32.W)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/NeoSyncBarChannel")
  )
}
