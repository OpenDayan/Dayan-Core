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

// Producer 指令，用于触发一组 Load/Store 任务
class MatProducerTaskGenCmd(params: MatParams) extends Bundle {
  val addr    = UInt(params.addrWidth.W)
  val stride  = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val major  = UInt(params.MNKWidth.W) // Major dec 1
  val repeat = UInt(params.MNKWidth.W) // Repeat dec 1
  val minor  = UInt(params.MNKWidth.W) // Minor dec 1
}

// 原始 DPArray 指令
class DPALoadCmd(params: MatParams, log2CountXTile: Int) extends Bundle {
  val regId = UInt(log2CountXTile.W)
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val zeroMask = UInt(math.max(math.max(params.cM, params.cN), params.busChannel).W)
  val byteZeroMask = UInt(params.channelWidth.W)
}

// MatProducer Ack
class MatProducerAck(params: MatParams, log2CountXTile: Int) extends Bundle {
  val regId = UInt(log2CountXTile.W)
}

/**
  * 矩阵Producer任务生成器（MatProducerTaskGen）
  *
  * ## 参数说明
  * - params: NMatParams
  *   - addrWidth: 地址位宽
  *   - MNKWidth: 主/副循环次数（Tile切分维度）
  *   - sM, sN, sK: DPArray形状，Tile寄存器数量
  *   - cM, cN, cK: 运算器尺寸
  * - log2CountXTile: Tile寄存器数量的对数，决定分配和回收指针宽度
  * - sMajor: major子分组位宽
  * - sMinor: minor子分组位宽
  * - majorStride: major步长，实际为运算器 major 维度尺寸
  * 
  * ## 接口说明
  * - io.cmdIn (Flipped Decoupled[MatProducerTaskGenCmd]):
  *     任务指令输入，包括 Tile 起始地址、步距、swizzle、major、repeat、minor 分组尺寸
  * - io.cmdOut (Decoupled[DPALoadCmd]):
  *     输出分配好寄存器ID、地址等信息的 Load / Store 子任务（一个 Tile 一个任务）
  * - io.ackIn (Input Bool):
  *     外部 Tile 读取完成的确认信号
  * - io.ackOut (Decoupled[MatProducerAck]):
  *     反馈给 Consumer 的完成信号，说明一个 Tile 已就绪，包含regId。
  * - io.releaseIn (Input Bool):
  *     Tile寄存器释放信号，用于回收 Tile，说明相应 Tile 不会被再次读取。
  *
  * ## 功能说明
  *   按照cmdIn接收到的Producer任务描述（起始地址、步进、切分主次序号、循环次数等），
  *   状态机遍历major/minor/repeat三重循环，分配Tile寄存器并生成Load/Store子任务命令序列输出给cmdOut。
  *   每次任务输出都分配一个regId，保持与Ack/Release计数、指针一致，确保不会资源冲突。子任务全部完成后才允许接受新任务，防止乱序。
  *   支持外部通过ackIn和releaseIn及时归还寄存器资源，保障并发与资源利用效率。
  */
class MatProducerTaskGen(params: MatParams, log2CountXTile: Int, sMajor: Int, sMinor: Int, majorStride: Int, minorStride: Int) extends Module {
  // IO
  val io = IO(new Bundle {
    val cmdIn = Flipped(Decoupled(new MatProducerTaskGenCmd(params)))
    val cmdOut = Decoupled(new DPALoadCmd(params, log2CountXTile))
    val ackIn = Input(Bool())
    val ackOut = Decoupled(new MatProducerAck(params, log2CountXTile))
    val releaseIn = Input(Bool())
  })

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)

  // TileRegister 分配及回收计数
  val trFreeCount = RegInit((1 << log2CountXTile).U)     // 可分配的表项数
  val trRdyCount  = RegInit(0.U((log2CountXTile + 1).W)) // 可响应的表项数

  // 指针
  val trAllocHead = RegInit(0.U(log2CountXTile.W))       // 分配头
  when(io.cmdOut.fire) {
    // TODO: Use Better Counter Logic(LFSR) Here.
    trAllocHead := trAllocHead + 1.U
  }
  io.cmdOut.valid := valid & (trFreeCount =/= 0.U)
  io.cmdOut.bits.regId := trAllocHead

  val trAckHead = RegInit(0.U(log2CountXTile.W))     // 响应头
  when(io.ackOut.fire) {
    // TODO: Use Better Counter Logic(LFSR) Here.
    trAckHead := trAckHead + 1.U
  }
  io.ackOut.valid := trRdyCount =/= 0.U
  io.ackOut.bits.regId := trAckHead

  // 分配回收计数更新
  trFreeCount := trFreeCount + io.releaseIn.asUInt - io.cmdOut.fire.asUInt
  trRdyCount := trRdyCount + io.ackIn.asUInt - io.ackOut.fire.asUInt

  // 主要循环状态机
  val curRepeat = Reg(UInt(params.MNKWidth.W))
  val curBaseMajor  = Reg(UInt(params.MNKWidth.W))
  val curBaseMinor  = Reg(UInt(params.MNKWidth.W))
  val curSubMajor = Reg(UInt(sMajor.W))
  val curSubMinor = Reg(UInt(sMinor.W))

  // 子分块边界计算
  val lastSubMajor = curSubMajor === (sMajor - 1).U
  val lastSubMinor = curSubMinor === (sMinor - 1).U

  // 计算真实的 Major Minor 下标
  val curMajor = curSubMajor | curBaseMajor
  val curMinor = curSubMinor | curBaseMinor

  // Debug 接口调试用
  dontTouch(curMajor)
  dontTouch(curMinor)

  // 真实边界计算
  val lastMajor = curMajor === cmd.major
  val lastRepeat = curRepeat === cmd.repeat
  val lastMinor = curMinor === cmd.minor

  // 结束情况
  finish := lastMajor & lastMinor & lastRepeat & io.cmdOut.fire

  // Valid 维护
  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  // 输入命令发射时，填入初始值
  when(io.cmdIn.fire) {
    curRepeat := 0.U
    curBaseMajor := 0.U
    curBaseMinor := 0.U
    curSubMajor := 0.U
    curSubMinor := 0.U
  }.elsewhen(io.cmdOut.fire) {
    when(lastMajor | lastSubMajor) {
      // Zigzag 遍历，之后可读取下一行
      curSubMajor := 0.U
      when(lastMinor | lastSubMinor) {
        curSubMinor := 0.U
        when(lastMajor) {
          // 循环完一行块
          curBaseMajor := 0.U
          when(lastRepeat) {
            // 不再需要循环该行
            curRepeat := 0.U
            curBaseMinor := curBaseMinor + sMinor.U
          }.otherwise {
            curRepeat := curRepeat + 1.U
          }
        }.otherwise {
          curBaseMajor := curBaseMajor + sMajor.U
        }
      }.otherwise {
        curSubMinor := curSubMinor + 1.U
      }
    }.otherwise {
      // 读取下一列
      curSubMajor := curSubMajor + 1.U
    }
  }

  // 生成控制命令
  io.cmdOut.bits.swizzle := cmd.swizzle
  io.cmdOut.bits.stride := cmd.stride
  io.cmdOut.bits.addr := cmd.addr + cmd.stride * curMajor * majorStride.U + minorStride.U * curMinor
  io.cmdOut.bits.zeroMask := 0.U
  io.cmdOut.bits.byteZeroMask := 0.U
}

object MatProducerTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatProducerTaskGen(MatParams(
      sM = 2, sN = 2, sK = 16
    ), 5, 2, 16, 16, 16),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatProducerTaskGen"
    )
  )
}
