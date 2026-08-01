/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatTask(params: MatParams) extends Bundle {
  val bf16 = Bool()
  val opMode = UInt(3.W) // ISA mode: mm=000, mma=001, conv2d=010, conv2da=011; inst[14:12]
  val isConv = Bool()
  val accumulate = Bool()
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

  val taskCi = UInt(params.MNKWidth.W) // CONV Ci 已按 BF16 / FP8 的不同情况，对齐到后端 K 维寻址尺度
  val taskWi = UInt(params.MNKWidth.W)
  val taskHi = UInt(params.MNKWidth.W)
  val taskWo = UInt(params.MNKWidth.W)
  val taskHo = UInt(params.MNKWidth.W)
  val taskWk = UInt(params.MNKWidth.W)
  val taskHk = UInt(params.MNKWidth.W)
  val taskCo = UInt(params.MNKWidth.W)
  val wiStride = UInt(params.addrWidth.W)
  val hiStride = UInt(params.addrWidth.W)
  val wkStride = UInt(params.addrWidth.W)
  val hkStride = UInt(params.addrWidth.W)
  val coStride = UInt(params.addrWidth.W)
  val woStride = UInt(params.addrWidth.W)
  val hoStride = UInt(params.addrWidth.W)
  val scaleWiStride = UInt(params.addrWidth.W)
  val scaleHiStride = UInt(params.addrWidth.W)
  val scaleWkStride = UInt(params.addrWidth.W)
  val scaleHkStride = UInt(params.addrWidth.W)
  val scaleCoStride = UInt(params.addrWidth.W)
  val padL = UInt(params.MNKWidth.W)
  val padU = UInt(params.MNKWidth.W)
  val dilationW = UInt(params.MNKWidth.W)
  val dilationH = UInt(params.MNKWidth.W)
  val strideW = UInt(params.MNKWidth.W)
  val strideH = UInt(params.MNKWidth.W)
  val strideDilationW = UInt(params.addrWidth.W)
  val strideDilationH = UInt(params.addrWidth.W)
}

/*
* 说明：
*  - 该模块用于矩阵乘法任务拆分，内部例化两个 Producer Task Gen，一个 Consumer Task Gen 及一个 PostProcessTaskGen
*  - 该模块对外，暴露三组指令接口(ldA, ldB, calc)，及对应指令的完成接口，及 Post-Processing 单元接口(cmd、allocate(Credit-FIFO))。
* */
class MatGemmTaskGen(params: MatParams) extends Module {
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
  val pA = Module(new MatProducerTaskGen(params, params.log2CountATile, params.sM, params.sK, params.cM, params.cK))
  val pB = Module(new MatProducerTaskGen(params, params.log2CountBTile, params.sN, params.sK, params.cN, params.cK))

  // Consumer 例化
  val c = Module(new MatConsumerTaskGen(params))

  // Post-Processor 例化
  val pp = Module(new MatPostProcessTaskGen(params))

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
  cCmd.bits.bf16  := io.cmdIn.bits.bf16

  val ppCmd = Wire(DecoupledIO(new MatPostProcessTaskGenCmd(params)))
  ppCmd.valid := io.cmdIn.fire
  ppCmd.bits.addr := io.cmdIn.bits.cBaseAddr
  ppCmd.bits.stride := io.cmdIn.bits.cStride
  ppCmd.bits.swizzle := io.cmdIn.bits.CSwizzleMBS
  ppCmd.bits.mTile := tileM
  ppCmd.bits.nTile := tileN
  ppCmd.bits.repeat := tileK >> params.log2SK
  ppCmd.bits.accumulate := io.cmdIn.bits.accumulate
  ppCmd.bits.lastTask := true.B
  ppCmd.bits.mMask := Fill(params.cM, 1.B)
  ppCmd.bits.nMask := Fill(params.cN, 1.B)

  // 命令连线
  pA.io.cmdIn <> Queue(aCmd, 2)
  pB.io.cmdIn <> Queue(bCmd, 2)
  c.io.cmdIn <> Queue(cCmd, 2)
  pp.io.cmdIn <> Queue(ppCmd, 2)

  io.cmdIn.ready := aCmd.ready & bCmd.ready & cCmd.ready & ppCmd.ready
}

/*
* 说明：
*  - 该模块根据指令模式，将 MAT 任务分发到 GEMM 或 CONV TaskGen。
*  - 同一时刻只允许一种任务类型占用后端接口；同类型任务仍可连续进入子 TaskGen 队列。
* */
class MatTaskGen(params: MatParams) extends Module {
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

  private val gemm = Module(new MatGemmTaskGen(params))
  private val conv = Module(new MatConvTaskGen(params))

  //表示当前执行的任务类型是 conv 还是 GEMM
  private val activeConv = RegInit(0.B)
  private val activeCount = RegInit(0.U(8.W))
  private val active = activeCount =/= 0.U
  //同一时间仅一个任务占用
  private val sameMode = !active | (io.cmdIn.bits.isConv === activeConv)

  gemm.io.cmdIn.bits := io.cmdIn.bits
  conv.io.cmdIn.bits := io.cmdIn.bits
  gemm.io.cmdIn.valid := io.cmdIn.valid & sameMode & !io.cmdIn.bits.isConv
  conv.io.cmdIn.valid := io.cmdIn.valid & sameMode & io.cmdIn.bits.isConv
  io.cmdIn.ready := sameMode & Mux(io.cmdIn.bits.isConv, conv.io.cmdIn.ready, gemm.io.cmdIn.ready)

  private val taskFinish = io.pp.fire & io.pp.bits.last
  when(io.cmdIn.fire & !active) {
    activeConv := io.cmdIn.bits.isConv
  }
  when(io.cmdIn.fire & !taskFinish) {
    activeCount := activeCount + 1.U
  }.elsewhen(!io.cmdIn.fire & taskFinish) {
    activeCount := activeCount - 1.U
  }

  gemm.io.ldAAck := io.ldAAck & !activeConv
  conv.io.ldAAck := io.ldAAck & activeConv
  gemm.io.ldARelease := io.ldARelease & !activeConv
  conv.io.ldARelease := io.ldARelease & activeConv
  gemm.io.ldBAck := io.ldBAck & !activeConv
  conv.io.ldBAck := io.ldBAck & activeConv
  gemm.io.ldBRelease := io.ldBRelease & !activeConv
  conv.io.ldBRelease := io.ldBRelease & activeConv

  io.ldA.valid := Mux(activeConv, conv.io.ldA.valid, gemm.io.ldA.valid)
  io.ldA.bits := gemm.io.ldA.bits
  when(activeConv) {
    io.ldA.bits := conv.io.ldA.bits
  }
  gemm.io.ldA.ready := io.ldA.ready & !activeConv
  conv.io.ldA.ready := io.ldA.ready & activeConv

  io.ldB.valid := Mux(activeConv, conv.io.ldB.valid, gemm.io.ldB.valid)
  io.ldB.bits := gemm.io.ldB.bits
  when(activeConv) {
    io.ldB.bits := conv.io.ldB.bits
  }
  gemm.io.ldB.ready := io.ldB.ready & !activeConv
  conv.io.ldB.ready := io.ldB.ready & activeConv

  io.calc.valid := Mux(activeConv, conv.io.calc.valid, gemm.io.calc.valid)
  io.calc.bits := gemm.io.calc.bits
  when(activeConv) {
    io.calc.bits := conv.io.calc.bits
  }
  gemm.io.calc.ready := io.calc.ready & !activeConv
  conv.io.calc.ready := io.calc.ready & activeConv

  io.pp.valid := Mux(activeConv, conv.io.pp.valid, gemm.io.pp.valid)
  io.pp.bits := gemm.io.pp.bits
  when(activeConv) {
    io.pp.bits := conv.io.pp.bits
  }
  gemm.io.pp.ready := io.pp.ready & !activeConv
  conv.io.pp.ready := io.pp.ready & activeConv

  gemm.io.ppAlloc.valid := io.ppAlloc.valid & !activeConv
  conv.io.ppAlloc.valid := io.ppAlloc.valid & activeConv
  gemm.io.ppAlloc.bits := io.ppAlloc.bits
  conv.io.ppAlloc.bits := io.ppAlloc.bits
  io.ppAlloc.ready := Mux(activeConv, conv.io.ppAlloc.ready, gemm.io.ppAlloc.ready)
}

object MatTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatTaskGen(MatParams(
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
