package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

case class NMatParams(
  // 地址位宽
  val addrWidth: Int = 20,
  val MNKWidth: Int = 16,
  // 描述 DPArray 形状
  // 存驻比
  sM: Int = 2, sN: Int = 2, sK: Int = 16,
  // 运算器尺寸
  cM: Int = 16, cN: Int = 16, cK: Int = 32
) {
  // Tile 寄存器数量
  val countATile = sM * sK
  val countBTile = sN * sK
  val countCTile = sM * sN
  val countPETile = sM * sN * 2
  val log2CountATile = log2Ceil(countATile)
  val log2CountBTile = log2Ceil(countBTile)
  val log2CountCTile = log2Ceil(countCTile)
  val log2CountPETile = log2Ceil(countPETile)
}

// Producer 指令，用于触发一组 Load/Store 任务
class MatProducerTaskGenCmd(params: NMatParams) extends Bundle {
  val addr    = UInt(params.addrWidth.W)
  val stride  = UInt(params.addrWidth.W)
  val swizzle = UInt(8.W)
  val major  = UInt(params.MNKWidth.W) // Major dec 1
  val repeat = UInt(params.MNKWidth.W) // Repeat dec 1
  val minor  = UInt(params.MNKWidth.W) // Minor dec 1
}

// 原始 DPArray 指令
class DPACalcCmd(params: NMatParams) extends Bundle {
  val regA = UInt(params.log2CountATile.W)
  val regB = UInt(params.log2CountATile.W)
  val regC = UInt(params.log2CountCTile.W)
  val clear = Bool()     // 清空累加器
  val writeback = Bool() // 读出到 PE 等待后处理及写回
}

class DPAPECmd(params: NMatParams) extends Bundle {
  val regId = UInt(params.log2CountPETile.W)
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(8.W)
  val clear = Bool() // 是否需要加原始输入
}

// 注意：所有指令顺序执行顺序完成，因此返回信号采用脉冲就绪格式。


