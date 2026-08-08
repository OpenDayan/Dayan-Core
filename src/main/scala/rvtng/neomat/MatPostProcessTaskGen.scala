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

// Post Process 指令，用于触发一组 Post-Processing 任务
class MatPostProcessTaskGenCmd(params: MatParams) extends Bundle {
  val addr    = UInt(params.addrWidth.W) // A 地址
  val stride  = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val mTile = UInt(params.MNKWidth.W) // M Tile Count dec 1
  val nTile = UInt(params.MNKWidth.W) // N Tile Count dec 1
  val repeat = UInt(params.MNKWidth.W) // K Tile Count / sK dec 1
  val accumulate = Bool() // mma or mm ?
  val lastTask = Bool()
  val mMask = UInt(params.cM.W)
  val nMask = UInt(params.cN.W)
}

// 后端实际执行的 Post Process 指令
class DPAPostProcessCmd(params: MatParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val clear = Bool() // 是否需要加原始输入，区别 mm / mma
  val last = Bool() // 最后一项请求，结束后返回 Finish 信号
  val mMask = UInt(params.cM.W)
  val nMask = UInt(params.cN.W)
}

/*
 * 接口：
 *  - 指向 Post Processor 的 PostProcess 指令
 * */
class MatPostProcessTaskGen(params: MatParams) extends Module {
  val io = IO(new Bundle {
    val cmdIn = Flipped(Decoupled(new MatPostProcessTaskGenCmd(params)))
    val cmdOut = Decoupled(new DPAPostProcessCmd(params))
  })

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)

  // 主要循环状态机
  val curRepeat = Reg(UInt(params.MNKWidth.W))
  val curBaseM = Reg(UInt(params.MNKWidth.W))
  val curBaseN = Reg(UInt(params.MNKWidth.W))
  val curSubM = Reg(UInt(params.log2SM.W))
  val curSubN = Reg(UInt(params.log2SN.W))

  // 子分块边界计算
  val lastSubM = curSubM === (params.sM - 1).U
  val lastSubN = curSubN === (params.sN - 1).U

  // 真实下标计算
  val curM = curBaseM | curSubM
  val curN = curBaseN | curSubN
  dontTouch(curM)
  dontTouch(curN)

  // 真实边界计算
  val lastM = curM === cmd.mTile
  val lastN = curN === cmd.nTile
  val lastRepeat = curRepeat === cmd.repeat

  // 结束情况
  finish := lastM & lastN & lastRepeat & io.cmdOut.fire

  // Valid 维护
  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  // Zig-Zag 遍历时写法，注意对称性
  when(io.cmdIn.fire) {
    curRepeat := 0.U
    curBaseM := 0.U
    curBaseN := 0.U
    curSubM := 0.U
    curSubN := 0.U
  }.elsewhen(io.cmdOut.fire) {
    when(lastSubN | lastN) {
      curSubN := 0.U
      // sM 进 1
      when(lastSubM | lastM) {
        curSubM := 0.U
        // Z 式遍历
        when(lastN) {
          curBaseN := 0.U
          // BaseM 进 1
          when(lastM) {
            curBaseM := 0.U
            // repeat 进 1
            curRepeat := curRepeat + 1.U
          }.otherwise {
            curBaseM := curBaseM + params.sM.U
          }
        }.otherwise {
          curBaseN := curBaseN + params.sN.U
        }
      }.otherwise {
        curSubM := curSubM + 1.U
      }
    }.otherwise {
      curSubN := curSubN + 1.U
    }
  }

  // 输出赋值
  io.cmdOut.valid := valid
  io.cmdOut.bits.clear := !cmd.accumulate & (curRepeat === 0.U)
  io.cmdOut.bits.swizzle := cmd.swizzle
  io.cmdOut.bits.stride := cmd.stride
  io.cmdOut.bits.addr := cmd.addr + cmd.stride * curM * params.cM.U + (4 * params.cN).U * curN
  io.cmdOut.bits.last := cmd.lastTask & lastM & lastN & lastRepeat
  io.cmdOut.bits.mMask := Mux(lastM, cmd.mMask, Fill(params.cM, 1.B))
  io.cmdOut.bits.nMask := Mux(lastN, cmd.nMask, Fill(params.cN, 1.B))

}
