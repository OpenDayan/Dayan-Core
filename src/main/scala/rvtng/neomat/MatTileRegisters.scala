package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import rvtng.utils.RF1R1WRamGenEN

// 这个模块是 Tile Register 的实现，注意， Tile Register 的数据是单周期读出的 SRAM 组织。
// 接口包含：
//  - 用于控制的指令接口 + 简化 URAM 接口
//  - 用于运算器读出的读接口（RegID + 单周期后返回查询结果）
class MatTileRegisters(params: NMatParams, log2CountXTile: Int, dataWidth: Int) extends Module {
  // 计算需要进行的请求数目
  val transactionCount = (dataWidth + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  val log2TransactionCount = log2Ceil(transactionCount)
  val io = IO(new Bundle {
    // 读取命令执行接口
    val cmdIn = Flipped(Decoupled(new DPALoadCmd(params, log2CountXTile)))
    // URAM 或者其它总线接口
    val busReq = Decoupled(new MatInnerTileReadReq(params, log2CountXTile, log2TransactionCount))
    val busResp = Vec(params.busChannel, new MatInnerTileChannelReadResp(params, log2CountXTile, log2TransactionCount))
    // 读取接口，单周期返回结果
    val re = Input(Bool())
    val regId = Input(UInt(log2CountXTile.W))
    val data = Output(Vec(dataWidth, UInt(8.W)))
  })

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)

  // 当前正在进行的 transaction id
  val curPos = Reg(UInt(log2TransactionCount.W))
  finish := curPos === (transactionCount - 1).U

  // Valid 维护
  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  when(io.cmdIn.fire) {
    curPos := 0.U
  }.elsewhen(io.busReq.fire) {
    curPos := curPos + 1.U
  }

  // 输入命令转 busReq
  // 注意，一条输入命令可能对应若干 busReq
  io.busReq.valid := valid
  io.busReq.bits.regId := cmd.regId
  // TODO: Make sure the multiplier here compiled to Mux.
  io.busReq.bits.addr := cmd.addr + curPos * cmd.stride * params.busChannel.U
  io.busReq.bits.stride := cmd.stride
  io.busReq.bits.swizzle := cmd.swizzle
  io.busReq.bits.position := curPos

  // 内部存储器结构: 1R1W
  val rawData = Wire(Vec(transactionCount, Vec(params.busChannel, Vec(params.channelWidth, UInt(8.W)))))
  for(pos <- 0 until transactionCount) {
    for(c <- 0 until params.busChannel) {
      val storage = Module(new RF1R1WRamGenEN(log2CountXTile, Vec(params.channelWidth, UInt(8.W))))
      storage.re    := io.re
      storage.raddr := io.regId
      rawData(pos)(c) := storage.rdata

      storage.we    := io.busResp(c).valid & (io.busResp(c).position === pos.U)
      storage.waddr := io.busResp(c).regId
      storage.wdata := io.busResp(c).data
    }
  }
  io.data := rawData.asTypeOf(io.data.cloneType)
}
