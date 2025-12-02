package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// 注意：该模块用于矩阵乘法后处理，包括简单激活、反量化、累加等操作
// 目前仅实现累加

class MatPostProcess(params: NMatParams) extends Module {
  val cTileSize = params.cM * params.cN * 4
  val cTransactionCount = (cTileSize + (params.busChannel * params.channelWidth) - 1) /
    (params.busChannel * params.channelWidth)
  val log2CTransactionCount = log2Ceil(cTransactionCount)
  val io = IO(new Bundle {
    // 来自 Controller 的 Post-Process 指令
    val pp = Flipped(Decoupled(new DPAPostProcessCmd(params)))
    // Post-Process 槽位分配接口
    val ppAlloc = Decoupled(Bool())

    // 来自 MatExec 的执行结果流水，全速率
    val result = Flipped(Decoupled(Vec(params.cM * params.cM, MatInnerSingedFP(params))))

    // URAM 读取接口，全速率
    val busRReq = Decoupled(new MatInnerTileReadReq(params, params.log2CountCTile, log2CTransactionCount))
    val busRResp = Vec(params.busChannel, new MatInnerTileChannelReadResp(params,
      params.log2CountCTile, log2CTransactionCount))

    // URAM 写回接口，低速率
    val busWReq = Vec(params.busWriteChannel,
      Decoupled(new MatInnerTileWriteReq(params, params.log2CountCTile, log2CTransactionCount))
    )
    val busWResp = Vec(params.busWriteChannel, new MatInnerTileChannelWriteResp(params,
      params.log2CountCTile, log2CTransactionCount))

    // 完成信号
    val finish = Output(Bool())
  })

  // 指令队列(写回地址等)

  // bus 读取结果队列
  val busReadQ = VecInit.tabulate(params.busChannel)(i =>
    Module(new Queue(Vec(params.channelWidth, UInt(8.W)), params.sM * params.sN)).io
  )
  // result 结果队列
  val resultQ = VecInit.tabulate(params.cM)(i =>
    Module(new Queue(Vec(params.cN, MatInnerSingedFP(params)), params.sM * params.sN)).io
  )

  // bus 写请求队列
  val busWriteQ = VecInit.tabulate(params.busWriteChannel)(i =>
    Module(new Queue(new MatInnerTileWriteReq(params, params.log2CountCTile, log2CTransactionCount), 2)).io
  )

  // bus 完成请求队列
  val busFinishQ = VecInit.tabulate(params.busWriteChannel)(i => {
      val m = Module(new Queue(Bool(), 2)).io
      m
    }
  )
  val busFinishAll = busFinishQ.map(i => i.deq.valid & i.deq.bits).reduce(_ | _)
  busFinishQ.zipWithIndex.foreach(iter => {
    val (busFinishIO, idx) = iter
    busFinishIO.enq.valid := io.busWResp(idx).valid
    busFinishIO.enq.bits := io.busWResp(idx).last
    busFinishIO.deq.ready := !busFinishIO.deq.bits || busFinishAll
  })
  io.finish := busFinishAll

}
