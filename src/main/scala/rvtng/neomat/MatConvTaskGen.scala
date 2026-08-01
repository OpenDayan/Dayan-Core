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

/*
* 说明：
*  - 该模块用于卷积任务拆分，内部例化两个 Conv Producer Task Gen，一个 Consumer Task Gen 及一个 PostProcessTaskGen
*  - 卷积复用原 GEMM 的 Z 式 Consumer：M->Wo tile，N->Co tile，K tile 解释为 (Hk, Wk, Ci) 内部 tile。
*  - 与 GEMM taskK 一样，cmd.taskCi 进入本模块前已经按 DataType 转为后端 K 维寻址尺度。
*  - Ho 由本模块作为外层子任务循环。
*  - padding 和 tail 由 Producer zeroMask / PostProcess mask 处理。
* */
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

  val ciTile = io.cmdIn.bits.taskCi >> params.log2CK
  val convKTileCount = (io.cmdIn.bits.taskHk + 1.U) * (io.cmdIn.bits.taskWk + 1.U) * (ciTile + 1.U)
  val convKTile = convKTileCount(params.MNKWidth - 1, 0) - 1.U

  // 任务拆分逻辑
  val tileM = io.cmdIn.bits.taskWo >> params.log2CM
  val tileN = io.cmdIn.bits.taskCo >> params.log2CN
  val tileK = convKTile
  val inputWoStride = io.cmdIn.bits.wiStride * io.cmdIn.bits.strideW
  val inputHoStride = io.cmdIn.bits.hiStride * io.cmdIn.bits.strideH
  val strideDilationW = Mux(
    io.cmdIn.bits.strideDilationW === 0.U,
    io.cmdIn.bits.wiStride * io.cmdIn.bits.dilationW,
    io.cmdIn.bits.strideDilationW
  )
  val strideDilationH = Mux(
    io.cmdIn.bits.strideDilationH === 0.U,
    io.cmdIn.bits.hiStride * io.cmdIn.bits.dilationH,
    io.cmdIn.bits.strideDilationH
  )
  val mTailMask = VecInit.tabulate(params.cM)(i =>
    i.U <= io.cmdIn.bits.taskWo(params.log2CM - 1, 0)
  ).asUInt
  val nTailMask = VecInit.tabulate(params.cN)(i =>
    i.U <= io.cmdIn.bits.taskCo(params.log2CN - 1, 0)
  ).asUInt

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)
  val cmdTileM = RegEnable(tileM, io.cmdIn.fire)
  val cmdTileN = RegEnable(tileN, io.cmdIn.fire)
  val cmdTileK = RegEnable(tileK, io.cmdIn.fire)
  val cmdInputWoStride = RegEnable(inputWoStride, io.cmdIn.fire)
  val cmdInputHoStride = RegEnable(inputHoStride, io.cmdIn.fire)
  val cmdStrideDilationW = RegEnable(strideDilationW, io.cmdIn.fire)
  val cmdStrideDilationH = RegEnable(strideDilationH, io.cmdIn.fire)
  val cmdMMask = RegEnable(mTailMask, io.cmdIn.fire)
  val cmdNMask = RegEnable(nTailMask, io.cmdIn.fire)

  // 卷积外层循环：Ho；每个 Ho 子任务内部仍按原 Consumer 的 Z 式 M/N/K tile 遍历。
  val curHo = Reg(UInt(params.MNKWidth.W))

  val lastHo = curHo === cmd.taskHo
  val lastSubTask = lastHo
  val issueSubTask = Wire(Bool())

  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  when(io.cmdIn.fire) {
    curHo := 0.U
  }.elsewhen(issueSubTask) {
    when(lastHo) {
      curHo := 0.U
    }.otherwise {
      curHo := curHo + 1.U
    }
  }

  // Producer 例化
  val pA = Module(new MatConvProducerTaskGen(params, params.log2CountATile, params.sM, params.sK, isKernel = false))
  val pB = Module(new MatConvProducerTaskGen(params, params.log2CountBTile, params.sN, params.sK, isKernel = true))

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

  val aCmd = Wire(DecoupledIO(new MatConvProducerTaskGenCmd(params)))
  aCmd.bits.addr := cmd.aBaseAddr
  aCmd.bits.swizzle := cmd.ASwizzleMBS
  aCmd.bits.major := cmdTileM
  aCmd.bits.minor := cmdTileK
  aCmd.bits.repeat := 0.U
  aCmd.bits.ho := curHo
  aCmd.bits.ciByteDec1 := cmd.taskCi
  aCmd.bits.taskWk := cmd.taskWk
  aCmd.bits.taskWo := cmd.taskWo
  aCmd.bits.taskWi := cmd.taskWi
  aCmd.bits.taskHi := cmd.taskHi
  aCmd.bits.taskCo := cmd.taskCo
  aCmd.bits.wiStride := cmd.wiStride
  aCmd.bits.hiStride := cmd.hiStride
  aCmd.bits.wkStride := cmd.wkStride
  aCmd.bits.hkStride := cmd.hkStride
  aCmd.bits.coStride := cmd.coStride
  aCmd.bits.inputWoStride := cmdInputWoStride
  aCmd.bits.inputHoStride := cmdInputHoStride
  aCmd.bits.strideDilationW := cmdStrideDilationW
  aCmd.bits.strideDilationH := cmdStrideDilationH
  aCmd.bits.padL := cmd.padL
  aCmd.bits.padU := cmd.padU
  aCmd.bits.strideW := cmd.strideW
  aCmd.bits.strideH := cmd.strideH
  aCmd.bits.dilationW := cmd.dilationW
  aCmd.bits.dilationH := cmd.dilationH

  val bCmd = Wire(DecoupledIO(new MatConvProducerTaskGenCmd(params)))
  bCmd.bits.addr := cmd.bBaseAddr
  bCmd.bits.swizzle := cmd.BSwizzleMBS
  bCmd.bits.major := cmdTileN
  bCmd.bits.minor := cmdTileK
  bCmd.bits.repeat := cmdTileM >> params.log2SM
  bCmd.bits.ho := curHo
  bCmd.bits.ciByteDec1 := cmd.taskCi
  bCmd.bits.taskWk := cmd.taskWk
  bCmd.bits.taskWo := cmd.taskWo
  bCmd.bits.taskWi := cmd.taskWi
  bCmd.bits.taskHi := cmd.taskHi
  bCmd.bits.taskCo := cmd.taskCo
  bCmd.bits.wiStride := cmd.wiStride
  bCmd.bits.hiStride := cmd.hiStride
  bCmd.bits.wkStride := cmd.wkStride
  bCmd.bits.hkStride := cmd.hkStride
  bCmd.bits.coStride := cmd.coStride
  bCmd.bits.inputWoStride := cmdInputWoStride
  bCmd.bits.inputHoStride := cmdInputHoStride
  bCmd.bits.strideDilationW := cmdStrideDilationW
  bCmd.bits.strideDilationH := cmdStrideDilationH
  bCmd.bits.padL := cmd.padL
  bCmd.bits.padU := cmd.padU
  bCmd.bits.strideW := cmd.strideW
  bCmd.bits.strideH := cmd.strideH
  bCmd.bits.dilationW := cmd.dilationW
  bCmd.bits.dilationH := cmd.dilationH

  val cCmd = Wire(DecoupledIO(new MatConsumerTaskGenCmd(params)))
  cCmd.bits.mTile := cmdTileM
  cCmd.bits.nTile := cmdTileN
  cCmd.bits.kTile := cmdTileK
  cCmd.bits.bf16 := cmd.bf16

  val ppCmd = Wire(DecoupledIO(new MatPostProcessTaskGenCmd(params)))
  ppCmd.bits.addr := cmd.cBaseAddr + curHo * cmd.hoStride
  ppCmd.bits.stride := cmd.woStride
  ppCmd.bits.swizzle := cmd.CSwizzleMBS
  ppCmd.bits.mTile := cmdTileM
  ppCmd.bits.nTile := cmdTileN
  ppCmd.bits.repeat := cmdTileK >> params.log2SK
  ppCmd.bits.accumulate := cmd.accumulate
  ppCmd.bits.lastTask := lastSubTask
  ppCmd.bits.mMask := cmdMMask
  ppCmd.bits.nMask := cmdNMask

  // 命令连线
  pA.io.cmdIn <> Queue(aCmd, 2)
  pB.io.cmdIn <> Queue(bCmd, 2)
  c.io.cmdIn <> Queue(cCmd, 2)
  pp.io.cmdIn <> Queue(ppCmd, 2)

  issueSubTask := valid & aCmd.ready & bCmd.ready & cCmd.ready & ppCmd.ready
  finish := issueSubTask & lastSubTask

  aCmd.valid := issueSubTask
  bCmd.valid := issueSubTask
  cCmd.valid := issueSubTask
  ppCmd.valid := issueSubTask

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
