package rvtng.utils

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._

/*
* 需求：
*    本模块功能类似记分板。数据转置以“访存组”为单位，但访存组内及组间的若干请求完成顺序不被总线保证，因而需要额外的同步机制。
* 本模块用于同时统计若干访存组已完成请求数量。当某个访存组已完成请求数量等于访存组总的请求数量时，说明相应访存组已就绪，可以发射执行转置输出。
*
* 端口：
*    完成接口：非阻塞，用于完成一个请求。输入访存组首个请求 ID 及访存组尺寸。（注：访存组尺寸数据输入为访存组尺寸 - 1）
*    检查接口：非阻塞，用于检查某一个访存组是否完成并标记发射，输入访存组首个请求 ID，及发射信号。当发射信号有效时，相应表项被标记为无效。
*
* 实现：
*    内部实现一个 [Depth] x [log2MaxGroupSize] 的 1RW 异步寄存器堆。（注：该寄存器堆需要支持复位到全 0 的初始状态）。
* 每当完成接口上出现完成请求时，异步读出相应 ID 对应的寄存器值，与总请求数量 - 1 做对比。当相等时，说明相应访存组就绪，向相同地址写回 0 。
* 否之，向相同地址写回寄存器旧值 + 1。
*    内部还需实现一个 [Depth] x 1b 的 1W1RW 异步寄存器堆。（注0：可使用 XOR 策略实现；注1:该寄存器堆需要支持复位到全 0 的初始状态）。
* 每当完成接口上的访存组就绪时，使用 1W 口向访存组首个请求 ID 对应表项写入 1。否之，不做更新。
*    检查接口的访存组首个请求 ID 直接接入 1RW 读写接口。当非发射时，只读。当发射时，对应表项写入 0。
*
* 未来优化：
*    实现部分的计数器，目前使用 + 1 方案。但如若相应路径处于关键路径，则应考虑使用 LFSR 计数器替代加法器，以降低逻辑延迟和面积。
*
*/
class ReorderScoreboard(depth: Int, maxGroupSize: Int) extends Module {
  val comp = IO(new Bundle {
    val valid = Input(Bool())
    val reqGroupID = Input(UInt(log2Ceil(depth).W))
    val reqGroupSize = Input(UInt(log2Ceil(maxGroupSize).W))
  })
  val check = IO(new Bundle {
    val fire = Input(Bool())
    val reqGroupID = Input(UInt(log2Ceil(depth).W))
    val reqGroupValid = Output(Bool())
  })

  val compFinish = Wire(Bool())

  if(maxGroupSize > 1) {
    // Counter Register File
    val counterRF = Module(new RF1RWAsyncRam(addressWidth = log2Ceil(depth), dataWidth = log2Ceil(maxGroupSize), selfReset = 1))
    counterRF.waddr := comp.reqGroupID
    counterRF.wdata := Mux(compFinish, 0.U, counterRF.rwdata + 1.U)
    counterRF.we := comp.valid
    compFinish := counterRF.rwdata === comp.reqGroupSize
  } else {
    compFinish := 1.B
  }

  // Valid Register File
  val validRF = Module(new XorRF2RWAsyncRam(addressWidth = log2Ceil(depth), dataWidth = 1, selfReset = 1))

  validRF.wp(0).we := comp.valid & compFinish
  validRF.wp(0).waddr := comp.reqGroupID
  validRF.wp(0).wdata := 1.U

  validRF.wp(1).we := check.fire
  validRF.wp(1).waddr := check.reqGroupID
  validRF.wp(1).wdata := 0.U
  check.reqGroupValid := validRF.wp(1).rwdata.asBool
}

object ReorderScoreboard extends App {
  ChiselStage.emitSystemVerilog(
    new ReorderScoreboard(512, 64),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/ReorderScoreboard"
    )
  )
}
