package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage


//Conv2D TaskGen - im2col

class MatConvTaskGen(params: MatParams) extends Module {
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

  //  M = Ho * Wo; N = Co; K = Hk * Wk * Ci
  private def dec1Product2(a: UInt, b: UInt): UInt = {
    val count = (a + 1.U) * (b + 1.U)
    (count - 1.U)(params.MNKWidth - 1, 0)
  }

  private def dec1Product3(a: UInt, b: UInt, c: UInt): UInt = {
    val count = (a + 1.U) * (b + 1.U) * (c + 1.U)
    (count - 1.U)(params.MNKWidth - 1, 0)
  }

  private def toAddrWidth(x: UInt): UInt = {
    val out = Wire(UInt(params.addrWidth.W))
    out := x
    out
  }

  private val convMDec1 = dec1Product2(io.cmdIn.bits.taskHo, io.cmdIn.bits.taskWo)
  private val convNDec1 = io.cmdIn.bits.taskCo
  private val convKElementDec1 = dec1Product3(
    io.cmdIn.bits.taskHk,
    io.cmdIn.bits.taskWk,
    io.cmdIn.bits.taskCi
  )
  private val convKByteDec1 = Wire(UInt(params.MNKWidth.W))
  convKByteDec1 := Mux(
    io.cmdIn.bits.bf16,
    Cat(convKElementDec1, 1.U(1.W))(params.MNKWidth - 1, 0),
    convKElementDec1
  )

  private val tileM = convMDec1 >> params.log2CM
  private val tileN = convNDec1 >> params.log2CN
  private val tileK = convKByteDec1 >> params.log2CK
  private val virtualInputRowStride = toAddrWidth(convKByteDec1 + 1.U)

  private val pA = Module(new MatProducerTaskGen(params, params.log2CountATile, params.sM, params.sK, params.cM, params.cK))
  private val pB = Module(new MatProducerTaskGen(params, params.log2CountBTile, params.sN, params.sK, params.cN, params.cK))
  private val c = Module(new MatConsumerTaskGen(params))
  private val pp = Module(new MatPostProcessTaskGen(params))

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

  val aCmd = Wire(DecoupledIO(new MatProducerTaskGenCmd(params)))
  aCmd.valid := io.cmdIn.fire
  aCmd.bits.addr := io.cmdIn.bits.aBaseAddr
  aCmd.bits.stride := virtualInputRowStride
  aCmd.bits.swizzle := io.cmdIn.bits.ASwizzleMBS
  aCmd.bits.major := tileM
  aCmd.bits.minor := tileK
  aCmd.bits.repeat := 0.U

  val bCmd = Wire(DecoupledIO(new MatProducerTaskGenCmd(params)))
  bCmd.valid := io.cmdIn.fire
  bCmd.bits.addr := io.cmdIn.bits.bBaseAddr
  bCmd.bits.stride := io.cmdIn.bits.coStride
  bCmd.bits.swizzle := io.cmdIn.bits.BSwizzleMBS
  bCmd.bits.major := tileN
  bCmd.bits.minor := tileK
  bCmd.bits.repeat := tileM >> params.log2SM

  val cCmd = Wire(DecoupledIO(new MatConsumerTaskGenCmd(params)))
  cCmd.valid := io.cmdIn.fire
  cCmd.bits.mTile := tileM
  cCmd.bits.nTile := tileN
  cCmd.bits.kTile := tileK
  cCmd.bits.bf16  := io.cmdIn.bits.bf16

  val ppCmd = Wire(DecoupledIO(new MatPostProcessTaskGenCmd(params)))
  ppCmd.valid := io.cmdIn.fire
  ppCmd.bits.addr := io.cmdIn.bits.cBaseAddr
  ppCmd.bits.stride := io.cmdIn.bits.woStride
  ppCmd.bits.swizzle := io.cmdIn.bits.CSwizzleMBS
  ppCmd.bits.mTile := tileM
  ppCmd.bits.nTile := tileN
  ppCmd.bits.repeat := tileK >> params.log2SK
  ppCmd.bits.accumulate := io.cmdIn.bits.accumulate

  pA.io.cmdIn <> Queue(aCmd, 2)
  pB.io.cmdIn <> Queue(bCmd, 2)
  c.io.cmdIn <> Queue(cCmd, 2)
  pp.io.cmdIn <> Queue(ppCmd, 2)

  io.cmdIn.ready := aCmd.ready & bCmd.ready & cCmd.ready & ppCmd.ready

  when(io.cmdIn.fire) {
    chisel3.assert(io.cmdIn.bits.isConv)
  }
}

object MatConvTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatConvTaskGen(MatParams(
      sM = 2, sN = 2, sK = 16
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatConvTaskGen"
    )
  )
}
