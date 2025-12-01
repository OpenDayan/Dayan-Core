package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatTask(params: NMatParams) extends Bundle {
  val bf16 = Bool()
  val mma = Bool() // 是否是 MMA 指令
  val aBaseAddr = UInt(params.addrWidth.W)
  val aStride   = UInt(params.addrWidth.W)
  val bBaseAddr = UInt(params.addrWidth.W)
  val bStride   = UInt(params.addrWidth.W)
  val cBaseAddr = UInt(params.addrWidth.W)
  val cStride   = UInt(params.addrWidth.W)
  val taskM = UInt(params.MNKWidth.W)
  val taskN = UInt(params.MNKWidth.W)
  val taskK = UInt(params.MNKWidth.W) // 注意： taskK 已按 BF16 / FP8 的不同情况，对齐到 Byte
  val ASwizzleMBS  = UInt(24.W)
  val BSwizzleMBS  = UInt(24.W)
  val CSwizzleMBS  = UInt(24.W)
  val persistentMode = UInt(2.W)
}

/*
* 说明：
*  - 该模块用于矩阵乘法任务拆分，内部例化两个 Producer Task Gen，一个 Consumer Task Gen 及一个 PostProcessTaskGen
*  - 该模块对外，暴露三组指令接口(ldA, ldB, calc)，及对应指令的完成接口，及 Post-Processing 单元接口(cmd、allocate(Credit-FIFO))。
* */
class MatTaskGen(params: NMatParams) extends Module {
  val io = IO(new Bundle{
    val cmdIn = Flipped(Decoupled(new MatTask(params)))
    val ldA = Decoupled(new DPALoadCmd(params, params.log2CountATile))
    val ldAAck = Input(Bool())
    val ldARelease = Input(Bool())
    val ldB = Decoupled(new DPALoadCmd(params, params.log2CountBTile))
    val ldBAck = Input(Bool())
    val ldBRelease = Input(Bool())
    val calc = Decoupled(new DPACalcCmd(params))
    val pp = Decoupled(new DPAPostProcessCmd(params))
    val ppAlloc = Flipped(Decoupled(Bool()))
  })

  // 任务拆分逻辑
  val tileM = io.cmdIn.bits.taskM >> (params.log2CM)
  val tileN = io.cmdIn.bits.taskN >> (params.log2CN)
  val tileK = io.cmdIn.bits.taskK >> (params.log2CK)

  // Producer 例化
  val pA = Module(new MatProducerTaskGen(params, params.log2CountATile, params.sM, params.sK, params.cK))
  val pB = Module(new MatProducerTaskGen(params, params.log2CountBTile, params.sN, params.sK, params.cK))

  // Consumer 例化
  val c = Module(new MatConsumerTaskGen(params))

  // Post-Processor 例化
  val pp = Module(new MatPostProcessTaskGen(params, 4 * params.cN))

  // 连线
  io.ldA <> Queue(pA.io.cmdOut, 2)
  pA.io.ackIn := io.ldAAck
  pA.io.releaseIn := io.ldARelease
  io.ldB <> Queue(pB.io.cmdOut, 2)
  pB.io.ackIn := io.ldBAck
  pB.io.releaseIn := io.ldBRelease

  c.io.aAck <> pA.io.ackOut
  c.io.bAck <> pB.io.ackOut
  io.calc <> Queue(c.io.cmdOut, 2)
  c.io.ppAlloc <> io.ppAlloc

  io.pp <> Queue(pp.io.cmdOut, 2)

  // 命令组装
  val aCmd = Wire(DecoupledIO(new MatProducerTaskGenCmd(params)))
  aCmd.valid := io.cmdIn.fire
  aCmd.bits.addr := io.cmdIn.bits.aBaseAddr
  aCmd.bits.stride := io.cmdIn.bits.aStride
  aCmd.bits.swizzle := io.cmdIn.bits.ASwizzleMBS
  aCmd.bits.major := tileM
  aCmd.bits.minor := tileK
  aCmd.bits.repeat := 0.U // Z Based, reuse a

  val bCmd = Wire(DecoupledIO(new MatProducerTaskGenCmd(params)))
  bCmd.valid := io.cmdIn.fire
  bCmd.bits.addr := io.cmdIn.bits.bBaseAddr
  bCmd.bits.stride := io.cmdIn.bits.bStride
  bCmd.bits.swizzle := io.cmdIn.bits.BSwizzleMBS
  bCmd.bits.major := tileN
  bCmd.bits.minor := tileK
  bCmd.bits.repeat := tileM >> params.log2SM

  val cCmd = Wire(DecoupledIO(new MatConsumerTaskGenCmd(params)))
  cCmd.valid := io.cmdIn.fire
  cCmd.bits.mTile := tileM
  cCmd.bits.nTile := tileN
  cCmd.bits.kTile := tileK

  val ppCmd = Wire(DecoupledIO(new MatPostProcessTaskGenCmd(params)))
  ppCmd.valid := io.cmdIn.fire
  ppCmd.bits.addr := io.cmdIn.bits.cBaseAddr
  ppCmd.bits.stride := io.cmdIn.bits.cStride
  ppCmd.bits.swizzle := io.cmdIn.bits.CSwizzleMBS
  ppCmd.bits.mTile := tileM
  ppCmd.bits.nTile := tileN
  ppCmd.bits.repeat := tileK >> params.log2SK
  ppCmd.bits.accumulate := io.cmdIn.bits.mma

  // 命令连线
  pA.io.cmdIn <> Queue(aCmd, 2)
  pB.io.cmdIn <> Queue(bCmd, 2)
  c.io.cmdIn <> Queue(cCmd, 2)
  pp.io.cmdIn <> Queue(ppCmd, 2)

  io.cmdIn.ready := aCmd.ready & bCmd.ready & cCmd.ready & ppCmd.ready
}

object MatTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatTaskGen(NMatParams(
      sM = 2, sN = 2, sK = 16
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatTaskGen"
    )
  )
}
