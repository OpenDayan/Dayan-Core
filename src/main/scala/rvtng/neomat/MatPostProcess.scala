package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import rvtng.fpmath.signedFP._

// 注意：该模块用于矩阵乘法后处理，包括简单激活、反量化、累加等操作
// 目前仅实现累加

class MatPostProcess(params: MatParams) extends Module {
  val cTileSize = params.cM * params.cN * 4
  val cReadTransactionCount = (cTileSize + (params.busChannel * params.channelWidth) - 1) /
    (params.busChannel * params.channelWidth)
  val log2CReadTransactionCount = log2Ceil(cReadTransactionCount)
  val cWriteTransactionCount = (cTileSize + (params.busWriteChannel * params.channelWidth) - 1) /
    (params.busWriteChannel * params.channelWidth)
  val log2CWriteTransactionCount = log2Ceil(cWriteTransactionCount)
  val io = IO(new Bundle {
    // 来自 Controller 的 Post-Process 指令
    val cmdIn = Flipped(Decoupled(new DPAPostProcessCmd(params)))
    // Post-Process 槽位分配接口
    val ppAlloc = Decoupled(Bool())

    // 来自 MatExec 的执行结果流水，全速率
    val result = Flipped(Decoupled(Vec(params.cM * params.cN, MatInnerSingedFP(params))))

    // URAM 读取接口，全速率
    val busRReq = Decoupled(new MatInnerTileReadReq(params, params.log2CountCTile, log2CReadTransactionCount))
    val busRResp = Input(Vec(params.busChannel, new MatInnerTileChannelReadResp(params,
      params.log2CountCTile, log2CReadTransactionCount)))

    // URAM 写回接口，低速率
    val busWReq = Vec(params.busWriteChannel,
      Decoupled(new MatInnerTileWriteReq(params))
    )
    val busWResp = Input(Vec(params.busWriteChannel, new MatInnerTileChannelWriteResp))

    // 完成信号
    val finish = Output(Bool())
  })

  // bus 读取结果队列
  val busReadInQ = VecInit.tabulate(params.cM)(i => {
    val m = Module(new Queue(Vec(params.cN, MatInnerSingedFP(params)), params.sM * params.sN)).io
    m
  })
  for(i <- 0 until params.cM) {
    busReadInQ(i).enq.valid := io.busRResp(i % params.busChannel).valid &&
      io.busRResp(i % params.busChannel).position === (i / params.busChannel).U
    val busReadData = VecInit.tabulate(params.channelWidth) { j =>
      Mux(io.busRResp(i % params.busChannel).zero | io.busRResp(i % params.busChannel).byteZeroMask(j),
        0.U, io.busRResp(i % params.busChannel).data(j))
    }
    val extFP32 = busReadData.asTypeOf(Vec(params.cN, UInt(32.W)))
    busReadInQ(i).enq.bits := VecInit(extFP32.map(SignedFPFrom(params.wE, params.wF, _)))
    busReadInQ(i).deq.ready := 0.B
  }
  // 重整 bus 读取结果
  val busReadQ = VecInit.tabulate(params.busWriteChannel)(i => {
    val m = Module(new Queue(Vec(params.cN, MatInnerSingedFP(params)), 2)).io
    m
  })
  for(i <- 0 until params.busWriteChannel) {
    val m = busReadQ(i)
    val curResultPos = RegInit(0.U(log2CWriteTransactionCount.W))
    m.enq.valid := busReadInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.valid
    m.enq.bits := busReadInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.bits
    busReadInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.ready := m.enq.ready
    curResultPos := curResultPos +
      busReadInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.fire.asUInt
  }

  // busQ 表项计数器
  val busReadInQFree = Wire(Vec(params.cM, Bool()))
  val busReadInQFreeCnt = RegInit(VecInit.tabulate(params.cM)(i => (params.sM * params.sN).U))
  busReadInQFreeCnt.zipWithIndex.foreach(iter => {
    val (cnt, idx) = iter
    val position = idx * cReadTransactionCount / params.cM
    cnt := cnt + busReadInQ(idx).deq.fire.asUInt - (io.busRReq.fire && io.busRReq.bits.position === position.U).asUInt
    busReadInQFree(idx) := cnt =/= 0.U
  })

  // result 结果队列
  io.result.ready := 1.B
  val resultInQ = VecInit.tabulate(params.cM)(i => {
    val m = Module(new Queue(Vec(params.cN, MatInnerSingedFP(params)), params.sM * params.sN)).io
    m
  })
  for(i <- 0 until params.cM) {
    val m = resultInQ(i)
    m.enq.valid := io.result.valid
    m.enq.bits := io.result.bits.asTypeOf(Vec(params.cM, Vec(params.cN, MatInnerSingedFP(params))))(i)
    chisel3.assert(!(m.enq.valid & !m.enq.ready))
    m.deq.ready := 0.B
  }

  // 重整输入
  val resultQ = VecInit.tabulate(params.busWriteChannel)(i => {
    val m = Module(new Queue(Vec(params.cN, MatInnerSingedFP(params)), 2)).io
    m
  })
  for(i <- 0 until params.busWriteChannel) {
    val m = resultQ(i)
    val curResultPos = RegInit(0.U(log2CWriteTransactionCount.W))
    m.enq.valid := resultInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.valid
    m.enq.bits := resultInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.bits
    resultInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.ready := m.enq.ready
    curResultPos := curResultPos +
      resultInQ((i.U + curResultPos * params.busWriteChannel.U)(log2Ceil(params.cM) - 1, 0)).deq.fire.asUInt
  }

  // result 表项计数器
  val resultInQFree = Wire(Vec(params.cM, Bool()))
  val resultInQFreeCnt = RegInit(VecInit.tabulate(params.cM)(i => (params.sM * params.sN).U))
  resultInQFreeCnt.zipWithIndex.foreach(iter => {
    val (cnt, idx) = iter
    cnt := cnt - io.ppAlloc.fire + resultInQ(idx).deq.fire.asUInt
    resultInQFree(idx) := cnt =/= 0.U
  })
  io.ppAlloc.valid := resultInQFree.reduceTree(_ & _)
  io.ppAlloc.bits := 0.B
  // bus 写地址队列
  class busWriteAddrT extends Bundle {
    val addr = UInt(params.addrWidth.W)
    val swizzle = UInt(24.W)
    val clear = Bool()
    val last = Bool()
    val valid = Bool()
    val nMask = UInt(params.cN.W)
  }
  val busWriteAddrQ = VecInit.tabulate(params.busWriteChannel)(i =>
    Module(new Queue(new busWriteAddrT, 2)).io
  )
  val busWriteAddrQRdy = VecInit(busWriteAddrQ.map(_.enq.ready)).reduceTree(_ & _)

  // pp cmdIn 信号拆分两组队列，分别负责 load 和 store 事务完成
  val loadCmdQ = Module(new Queue(new DPAPostProcessCmd(params), 2)).io
  val storeCmdQ = Module(new Queue(new DPAPostProcessCmd(params), 2)).io
  io.cmdIn.ready := loadCmdQ.enq.ready & storeCmdQ.enq.ready
  loadCmdQ.enq.valid  := io.cmdIn.fire & !io.cmdIn.bits.clear
  storeCmdQ.enq.valid := io.cmdIn.fire
  loadCmdQ.enq.bits := io.cmdIn.bits
  storeCmdQ.enq.bits := io.cmdIn.bits

  // 写入地址生成逻辑
  // 当前正在处理任务的信号
  val writeValid = RegInit(0.B)
  val writeFinish = Wire(Bool())
  val writeCmd = RegEnable(storeCmdQ.deq.bits, storeCmdQ.deq.fire)
  storeCmdQ.deq.ready := writeFinish | !writeValid
  val busWriteAddrQFire = busWriteAddrQRdy & writeValid
  val curWritePosition = Reg(UInt(log2CWriteTransactionCount.W))
  when(storeCmdQ.deq.fire) {
    writeValid := 1.B
  }.elsewhen(writeFinish) {
    writeValid := 0.B
  }
  when(storeCmdQ.deq.fire) {
    curWritePosition := 0.U
  }.elsewhen(busWriteAddrQFire) {
    curWritePosition := curWritePosition + 1.U
  }
  val writeLast = curWritePosition === (cWriteTransactionCount - 1).U
  writeFinish := writeLast & busWriteAddrQFire
  busWriteAddrQ.zipWithIndex.foreach(iter => {
    val (q, idx) = iter
    val curM = idx.U + curWritePosition * params.busWriteChannel.U
    val curMValid = writeCmd.mMask(curM(log2Ceil(params.cM) - 1, 0))
    val curMAddr = writeCmd.addr + curM * writeCmd.stride
    q.enq.valid := busWriteAddrQFire
    q.enq.bits.addr := Mux(curMValid, curMAddr, writeCmd.addr)
    q.enq.bits.swizzle := writeCmd.swizzle
    q.enq.bits.clear := writeCmd.clear
    q.enq.bits.last := writeCmd.last && writeLast
    q.enq.bits.valid := curMValid
    q.enq.bits.nMask := writeCmd.nMask
  })

  // 读取地址生成逻辑
  // 当前正在处理任务的信号
  val readValid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val readFinish = Wire(Bool())
  loadCmdQ.deq.ready := readFinish | !readValid
  val readCmd = RegEnable(loadCmdQ.deq.bits, loadCmdQ.deq.fire)
  when(loadCmdQ.deq.fire) {
    readValid := 1.B
  }.elsewhen(readFinish) {
    readValid := 0.B
  }
  val curReadPosition = Reg(UInt(log2CReadTransactionCount.W))
  io.busRReq.valid := readValid & busReadInQFree.reduceTree(_ & _)
  readFinish := (curReadPosition === (cReadTransactionCount - 1).U) && io.busRReq.fire
  when(loadCmdQ.deq.fire) {
    curReadPosition := 0.U
  }.elsewhen(io.busRReq.fire) {
    curReadPosition := curReadPosition + 1.U
  }
  io.busRReq.bits.regId := 0.U // No Use
  io.busRReq.bits.position := curReadPosition
  io.busRReq.bits.addr := readCmd.addr + curReadPosition * (params.cM / cReadTransactionCount).U * readCmd.stride
  io.busRReq.bits.stride := readCmd.stride
  io.busRReq.bits.swizzle := readCmd.swizzle
  io.busRReq.bits.last := 0.B
  io.busRReq.bits.zeroMask := VecInit.tabulate(params.busChannel) { i =>
    val curM = i.U + curReadPosition * params.busChannel.U
    !readCmd.mMask(curM(log2Ceil(params.cM) - 1, 0))
  }.asUInt
  io.busRReq.bits.byteZeroMask := VecInit.tabulate(params.channelWidth) { i =>
    !readCmd.nMask(i / 4)
  }.asUInt

  // bus 完成请求队列
  val busFinishQ = VecInit.tabulate(params.busWriteChannel)(i => {
      Module(new Queue(Bool(), 2)).io
    }
  )

  // URAM 接线逻辑

  val busFinishAll = VecInit(busFinishQ.map(i => i.deq.valid & i.deq.bits)).reduceTree(_ & _)
  busFinishQ.zipWithIndex.foreach(iter => {
    val (busFinishIO, idx) = iter
    busFinishIO.enq.valid := io.busWResp(idx).valid
    busFinishIO.enq.bits := io.busWResp(idx).last
    busFinishIO.deq.ready := !busFinishIO.deq.bits || busFinishAll
  })
  io.finish := busFinishAll

  // 计算流水线
  // 获取 resultQ, busWriteAddrQ 压入 busWriteQ
  // 打两级流水
  val busWReqPipe = Wire(Vec(params.busWriteChannel,
    DecoupledIO(new MatInnerTileWriteReq(params))
  ))
  io.busWReq <> VecInit(busWReqPipe.map(it => Queue(Queue(it, 1, true, false), 1, true, false)))

  resultQ.zip(busReadQ).zip(busWriteAddrQ).zip(busWReqPipe).foreach(iter => {
    val (((lQ, rQ), aQ), tQ) = iter
    tQ.valid := lQ.deq.valid & aQ.deq.valid & (aQ.deq.bits.clear | rQ.deq.valid)
    // TODO: 换用更好的浮点加法器
    tQ.bits.data := VecInit(lQ.deq.bits.zip(rQ.deq.bits).map(adders => {
      val fpAdder = Module(new KulischAccumulator(KulischAccConfig(
        numInputs = 2,
        inExpWidth = params.wE,
        inSigWidth = params.wF,
        outExpWidth = params.wE,
        outSigWidth = params.wF + 1
      )))
      val zeroFp = Wire(adders._2.cloneType)
      zeroFp.exp := (BigInt(1) << (params.wE-1)).S(params.wE.W)
      zeroFp.frac := 0.S
      fpAdder.io.in(0) := adders._1
      fpAdder.io.in(1) := Mux(aQ.deq.bits.clear, zeroFp, adders._2)
      FNFromSignedFP(fpAdder.io.out)
    })).asTypeOf(tQ.bits.data)
    tQ.bits.last := aQ.deq.bits.last
    tQ.bits.addr := aQ.deq.bits.addr
    tQ.bits.swizzle := aQ.deq.bits.swizzle
    tQ.bits.byteEnable := VecInit.tabulate(params.channelWidth) { i =>
      aQ.deq.bits.valid & aQ.deq.bits.nMask(i / 4)
    }.asUInt
    lQ.deq.ready := tQ.fire
    rQ.deq.ready := tQ.fire & !aQ.deq.bits.clear
    aQ.deq.ready := tQ.fire
  })
}
