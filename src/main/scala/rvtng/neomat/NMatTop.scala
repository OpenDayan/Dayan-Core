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
  val log2SM = log2Ceil(sM)
  val log2SN = log2Ceil(sN)
  val log2SK = log2Ceil(sK)
  val log2CM = log2Ceil(cM)
  val log2CN = log2Ceil(cN)
  val log2CK = log2Ceil(cK)
  val countATile = sM * sK
  val countBTile = sN * sK
  val countCTile = sM * sN
  val countPETile = sM * sN * 2
  val log2CountATile = log2Ceil(countATile)
  val log2CountBTile = log2Ceil(countBTile)
  val log2CountCTile = log2Ceil(countCTile)
  val log2CountPETile = log2Ceil(countPETile)
}

// 注意：所有指令顺序执行顺序完成，因此返回信号采用脉冲就绪格式。


