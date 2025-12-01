package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage


// 原始 DPArray 指令
class DPALoadCmd(params: NMatParams, log2CountXTile: Int) extends Bundle {
  val regId = UInt(log2CountXTile.W)
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(8.W)
}

// MatProducer Ack
class MatProducerAck(params: NMatParams, log2CountXTile: Int) extends Bundle {
  val regId = UInt(log2CountXTile.W)
}

class MatProducerTaskGen(params: NMatParams, log2CountXTile: Int, sMajor: Int, sMinor: Int, majorStride: Int) extends Module {
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
        curSubMinor := 0.U
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
  io.cmdOut.bits.addr := cmd.addr + cmd.stride * (majorStride.U * curMajor + curMinor)
}

object MatProducerTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatProducerTaskGen(NMatParams(), 5, 2, 16, 16),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatProducerTaskGen"
    )
  )
}

