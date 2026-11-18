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

package dayan.rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import dayan.rvtng.fpmath.DPArray.{FP8BF16FusedDPArray, U8m2n2k1U4m2n2k2Mul}
import dayan.rvtng.fpmath.signedFP.SignedFP
import dayan.rvtng.utils.genPipe

// 内部总线抽象
class MatInnerTileReadReq(params: MatParams, regIdWidth: Int, posWidth: Int) extends Bundle {
  val regId = UInt(regIdWidth.W)
  val position = UInt(posWidth.W) // 一个 Tile Register Fetch 请求，可能会切分为多个 transaction 去执行，此时用 position 区别。
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val last = Bool() // 最后一个请求
  val zeroMask = UInt(params.busChannel.W)
  val byteZeroMask = UInt(params.channelWidth.W)
}

class MatInnerTileChannelReadResp(params: MatParams, regIdWidth: Int, posWidth: Int) extends Bundle {
  val valid = Bool()
  val regId = UInt(regIdWidth.W)
  val position = UInt(posWidth.W) // 一个 Tile Register Fetch 请求，可能会切分为多个 transaction 去执行，此时用 position 区别。
  val data = Vec(params.channelWidth, UInt(8.W))
  val zero = Bool()
  val byteZeroMask = UInt(params.channelWidth.W)
}

class MatInnerTileWriteReq(params: MatParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val last = Bool()
  val byteEnable = UInt(params.channelWidth.W)
  val data = Vec(params.channelWidth, UInt(8.W))
}

class MatInnerTileChannelWriteResp extends Bundle {
  val valid = Bool()
  val last = Bool()
}

// 该模块作为执行后段，接收来自前端发来的 load / calc 指令，完成矩阵乘法运算并将结果流式输出给 PE 做后处理。
class MatExec(params: MatParams) extends Module {
  val aTileSize = params.cM * params.cK
  val bTileSize = params.cN * params.cK
  val aTransactionCount = (aTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  val log2ATransactionCount = log2Ceil(aTransactionCount)
  val bTransactionCount = (bTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  val log2BTransactionCount = log2Ceil(bTransactionCount)
  val io = IO(new Bundle {
    val ldA = Flipped(Decoupled(new DPALoadCmd(params, params.log2CountATile)))
    val ldAAck = Output(Bool())
    val ldARelease = Output(Bool())
    val ldB = Flipped(Decoupled(new DPALoadCmd(params, params.log2CountBTile)))
    val ldBAck = Output(Bool())
    val ldBRelease = Output(Bool())
    val calc = Flipped(Decoupled(new DPACalcCmd(params)))

    // 结果流式输出
    val result = Decoupled(Vec(params.cM * params.cN, params.accumulatorType))

    // 简化总线接口
    val busAReq = Decoupled(new MatInnerTileReadReq(params, params.log2CountATile, log2ATransactionCount))
    val busBReq = Decoupled(new MatInnerTileReadReq(params, params.log2CountBTile, log2BTransactionCount))
    val busAResp = Input(Vec(params.busChannel, new MatInnerTileChannelReadResp(params, params.log2CountATile, log2ATransactionCount)))
    val busBResp = Input(Vec(params.busChannel, new MatInnerTileChannelReadResp(params, params.log2CountBTile, log2BTransactionCount)))
    val busAAck = Input(Bool())
    val busBAck = Input(Bool())
  })

  // BUS 2B View
  val busDataA_2B = VecInit(io.busAResp.map(_.data)).asTypeOf(Vec(params.busChannel, Vec(params.channelWidth / 2, UInt(16.W))))
  val busDataB_2B = VecInit(io.busBResp.map(_.data)).asTypeOf(Vec(params.busChannel, Vec(params.channelWidth / 2, UInt(16.W))))
  dontTouch(busDataA_2B)
  dontTouch(busDataB_2B)

  // Tile Register
  val aTile = Module(new MatTileRegisters(params, params.log2CountATile, aTileSize))
  val bTile = Module(new MatTileRegisters(params, params.log2CountBTile, bTileSize))

  // Tile Command Interface
  aTile.io.cmdIn <> io.ldA
  io.ldAAck := io.busAAck
  io.busAReq <> aTile.io.busReq
  aTile.io.busResp := io.busAResp
  bTile.io.cmdIn <> io.ldB
  io.ldBAck := io.busBAck
  io.busBReq <> bTile.io.busReq
  bTile.io.busResp := io.busBResp

  // Calc Handshake
  val cRegPipe = RegInit(0.U.asTypeOf(Vec(3, new Bundle {
    val valid = Bool()
    val regId = UInt(params.log2CountCTile.W)
  })))
  cRegPipe.foldLeft({
    val initV = Wire(cRegPipe(0).cloneType)
    initV.valid := io.calc.fire
    initV.regId := io.calc.bits.regC
    initV
  })((l, r) => {
    r := l
    r
  })

  // Note: prevent C data raw hazard.
  io.calc.ready := !VecInit(cRegPipe.map(iter => {
    iter.valid & (iter.regId === io.calc.bits.regC)
  })).reduceTree(_ | _)

  // Early Release of Tile
  io.ldARelease := io.calc.fire & io.calc.bits.releaseA
  io.ldBRelease := io.calc.fire & io.calc.bits.releaseB

  aTile.io.re := io.calc.fire
  bTile.io.re := io.calc.fire
  aTile.io.regId := io.calc.bits.regA
  bTile.io.regId := io.calc.bits.regB
  val a = aTile.io.data.asTypeOf(Vec(params.cM, Vec(params.cK, UInt(8.W))))
  val b = bTile.io.data.asTypeOf(Vec(params.cN, Vec(params.cK, UInt(8.W))))
  val cTile = Reg(Vec(params.countCTile, Vec(params.cM * params.cN, params.accumulatorType)))
  val dpaOut = Wire(chiselTypeOf(io.result.bits))

  val (resultValid, resultCmd) = params.computeType match {
    case MatComputeType.FP8BF16 =>
      val aTileFP8 = dontTouch(a)
      val bTileFP8 = dontTouch(b)
      val aTileBF16 = aTile.io.data.asTypeOf(Vec(params.cM, Vec(params.cK / 2, UInt(16.W))))
      val bTileBF16 = bTile.io.data.asTypeOf(Vec(params.cN, Vec(params.cK / 2, UInt(16.W))))
      dontTouch(aTileBF16)
      dontTouch(bTileBF16)

      val dpa = Module(new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
        M = params.cM, N = params.cN, K = params.cK, sK = params.cK,
        wE = params.wE, wF = params.wF,
        debugFP32Output = true, enableStickBit = false, wMul = 17
      ))
      dpa.valid := RegNext(io.calc.fire)
      dpa.bf16 := RegNext(io.calc.bits.bf16)
      dpa.a := aTileFP8
      dpa.b := bTileFP8
      dpa.a_scale := 0.U.asTypeOf(dpa.a_scale)
      dpa.b_scale := 0.U.asTypeOf(dpa.b_scale)

      val latency = dpa.r0Latency + 3
      val valid = genPipe(io.calc.fire, latency)
      val cmd = genPipe(io.calc.bits, latency)
      val cin = cTile(cmd(dpa.r0Latency).regC).asTypeOf(
        Vec(params.cM, Vec(params.cN, new SignedFP(params.wE, params.wF))))
      val clear = !valid(dpa.r0Latency) || cmd(dpa.r0Latency).clear
      dpa.r0_cin_exp_in := RegNext(VecInit.tabulate(params.cM, params.cN) { (m, n) =>
        Mux(clear, (BigInt(-1) << (params.wE - 1)).S(params.wE.W), cin(m)(n).exp)
      })
      dpa.r0_cin_frac_in := RegNext(VecInit.tabulate(params.cM, params.cN) { (m, n) =>
        Mux(clear, 0.S(params.wF.W), cin(m)(n).frac)
      })
      val out = Wire(Vec(params.cM * params.cN, new SignedFP(params.wE, params.wF)))
      for (m <- 0 until params.cM; n <- 0 until params.cN) {
        out(m * params.cN + n).exp := dpa.cout_exp(m)(n)
        out(m * params.cN + n).frac := dpa.cout_frac(m)(n)
      }
      dpaOut := out
      (valid(latency), cmd(latency))

    case MatComputeType.Int8 =>
      val dpa = Module(new Int8DPArray(params.cM, params.cN, params.cK))
      dpa.io.a := a
      dpa.io.b := b
      // One cycle for the tile read, then two cycles for the dot product.
      val latency = 1 + dpa.latency
      val valid = genPipe(io.calc.fire, latency)(latency)
      val cmd = genPipe(io.calc.bits, latency)(latency)
      val cin = cTile(cmd.regC).asTypeOf(chiselTypeOf(dpa.io.cin))
      dpa.io.cin := Mux(cmd.clear, 0.U.asTypeOf(cin), cin)
      dpaOut := dpa.io.cout
      (valid, cmd)
  }

  when(resultValid) {
    cTile(resultCmd.regC) := dpaOut
  }

  io.result.valid := resultValid & resultCmd.writeback
  io.result.bits := dpaOut

  chisel3.assert(!(io.result.valid & !io.result.ready))
}

object MatExec extends App {
  ChiselStage.emitSystemVerilog(
    new MatExec(MatParams(
      sM = 2, sN = 2, sK = 16
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatExec"
    )
  )
}
