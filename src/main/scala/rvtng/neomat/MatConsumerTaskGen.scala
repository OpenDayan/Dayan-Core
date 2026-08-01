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

// Consumer 指令，用于触发一组 Calc 任务
class MatConsumerTaskGenCmd(params: MatParams) extends Bundle {
  val mTile = UInt(params.MNKWidth.W) // M Tile Count
  val nTile = UInt(params.MNKWidth.W) // N Tile Count
  val kTile = UInt(params.MNKWidth.W) // K Tile Count
  val bf16 = Bool()
}

// 原始 DPArray 指令
class DPACalcCmd(params: MatParams) extends Bundle {
  val regA = UInt(params.log2CountATile.W)
  val regB = UInt(params.log2CountATile.W)
  val regC = UInt(params.log2CountCTile.W)
  val releaseA = Bool() // 读出数据后立即释放一个 A Tile，可以提前释放以掩盖延迟
  val releaseB = Bool() // 读出数据后立即释放一个 B Tile，可以提前释放以掩盖延迟
  val bf16 = Bool() // 使用 BF16
  val clear = Bool()     // 清空累加器
  val writeback = Bool() // 读出到 PE 等待后处理及写回
}

// 接口：来自 A、B 的 Producer Ack 接口和 Release 接口，用于适时的获取 Tile 并释放 Tile Register
// 接口：来自 PE Controller 的 Acquire 接口，用于适时的获取 C Tile Buffer Space，并进行反压避免 Overflow

class MatConsumerTaskGen(params: MatParams) extends Module {
  val io = IO(new Bundle {
    val cmdIn = Flipped(Decoupled(new MatConsumerTaskGenCmd(params)))
    // Note: User may add flip-flop in cmdOut Interface for better timming.
    val cmdOut = Decoupled(new DPACalcCmd(params))
    val aAck = Flipped(Decoupled(new MatProducerAck(params, params.log2CountATile)))
    val bAck = Flipped(Decoupled(new MatProducerAck(params, params.log2CountBTile)))
    val ppAlloc = Flipped(Decoupled(Bool()))
  })

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)

  // 内部记分板，记录 Mat Tile -> RegID 的映射关系
  // 1r1w, 1r 用于命令生成，1w来自 Producer 接口
  //  val aScoreBoard = Reg(Vec(params.sM, Vec(params.sK, UInt(params.log2CountATile.W))))
  //  val bScoreBoard = Reg(Vec(params.sN, Vec(params.sK, UInt(params.log2CountBTile.W))))

  val aScoreBoard = Mem(params.countATile, UInt(params.log2CountATile.W))
  val bScoreBoard = Mem(params.countATile, UInt(params.log2CountATile.W))

  // 主要循环状态机
  val curBaseM = Reg(UInt(params.MNKWidth.W))
  val curBaseN = Reg(UInt(params.MNKWidth.W))
  val curBaseK = Reg(UInt(params.MNKWidth.W))
  val curSubM = Reg(UInt(params.log2SM.W))
  val curSubN = Reg(UInt(params.log2SN.W))
  val curSubK = Reg(UInt(params.log2SK.W))

  // 子分块边界计算
  val lastSubM = curSubM === (params.sM - 1).U
  val lastSubN = curSubN === (params.sN - 1).U
  val lastSubK = curSubK === (params.sK - 1).U

  // 真实下标计算
  val curM = curBaseM | curSubM
  val curN = curBaseN | curSubN
  val curK = curBaseK | curSubK

  // Debug 接口调试用
  dontTouch(curM)
  dontTouch(curN)
  dontTouch(curK)

  // 真实边界计算
  val lastM = curM === cmd.mTile
  val lastN = curN === cmd.nTile
  val lastK = curK === cmd.kTile

  // 结束情况
  finish := lastM & lastN & lastK & io.cmdOut.fire

  // Valid 维护
  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  when(io.cmdIn.fire) {
    curBaseM := 0.U
    curBaseN := 0.U
    curBaseK := 0.U
    curSubM := 0.U
    curSubN := 0.U
    curSubK := 0.U
  }.elsewhen(io.cmdOut.fire) {
    when(lastSubN | lastN) {
      curSubN := 0.U
      // sM 进 1
      when(lastSubM | lastM) {
        curSubM := 0.U
        // sK 进 1
        when(lastSubK | lastK) {
          curSubK := 0.U
          // Z 式遍历
          when(lastN) {
            curBaseN := 0.U
            // BaseM 进 1
            when(lastM) {
              curBaseM := 0.U
              // BaseK 进 1
              curBaseK := curBaseK + params.sK.U
            }.otherwise {
              curBaseM := curBaseM + params.sM.U
            }
          }.otherwise {
            curBaseN := curBaseN + params.sN.U
          }
        }.otherwise {
          curSubK := curSubK + 1.U
        }
      }.otherwise {
        curSubM := curSubM + 1.U
      }
    }.otherwise {
      curSubN := curSubN + 1.U
    }
  }

  // 当前请求元信息
  val cClear = curSubK === 0.U
  val cWriteback = lastK | lastSubK
  val aFetch = curN === 0.U
  val bFetch = curSubM === 0.U
  val aRelease = lastN
  val bRelease = lastM | lastSubM
  io.cmdOut.valid := valid & (!aFetch | io.aAck.valid) & (!bFetch | io.bAck.valid) & (!cWriteback | io.ppAlloc.valid)
  io.cmdOut.bits.regA := Mux(aFetch, io.aAck.bits.regId,
  //  aScoreBoard(curSubM)(curSubK)
    aScoreBoard.read((curSubM << params.log2SK).asUInt | curSubK)
  )
  io.cmdOut.bits.regB := Mux(bFetch, io.bAck.bits.regId,
  //  bScoreBoard(curSubN)(curSubK)
    bScoreBoard.read((curSubN << params.log2SK).asUInt | curSubK)
  )
  io.cmdOut.bits.regC := (curSubM << params.log2SN).asUInt | curSubN
  io.cmdOut.bits.releaseA := aRelease
  io.cmdOut.bits.releaseB := bRelease
  io.cmdOut.bits.bf16 := cmd.bf16
  io.cmdOut.bits.clear := cClear
  io.cmdOut.bits.writeback := cWriteback

  io.aAck.ready := aFetch & io.cmdOut.fire
  io.bAck.ready := bFetch & io.cmdOut.fire
  io.ppAlloc.ready := cWriteback & io.cmdOut.fire

  // 更新 Scoreboard
  when(io.aAck.fire) {
//    aScoreBoard(curSubM)(curSubK) := io.aAck.bits.regId
    aScoreBoard((curSubM << params.log2SK).asUInt | curSubK) := io.aAck.bits.regId
  }
  when(io.bAck.fire) {
//    bScoreBoard(curSubN)(curSubK) := io.bAck.bits.regId
    bScoreBoard((curSubN << params.log2SK).asUInt | curSubK) := io.bAck.bits.regId
  }

}
