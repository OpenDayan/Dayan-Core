package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Post Process 指令，用于触发一组 Post-Processing 任务
class MatPostProcessTaskGenCmd(params: NMatParams) extends Bundle {
  val addr    = UInt(params.addrWidth.W) // A 地址
  val stride  = UInt(params.addrWidth.W)
  val swizzle = UInt(8.W)
  val mTile = UInt(params.MNKWidth.W) // M Tile Count
  val nTile = UInt(params.MNKWidth.W) // N Tile Count
  val repeat = UInt(params.MNKWidth.W) // K Tile Count / sK
}

// 后端实际执行的 Post Process 指令
class DPAPostProcessCmd(params: NMatParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(8.W)
  val clear = Bool() // 是否需要加原始输入，区别 mm / mma
}

/*
 * 接口：
 *  - 指向 Post Processor 的 PostProcess 指令
 * */
class MatPostProcessTaskGen(params: NMatParams) extends Module {
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

}
