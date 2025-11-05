package rvtng.vec

import chisel3._
import chisel3.util._
import rvtng.utils.PartialAssign

// 双级流水 Regfile，读取数据并等待送入之后的 FU 进行执行

class VecInstRData(params: VecParams) extends VecInstWThreadInfo(params) {
  val vsrc1Data = Vec(params.laneWidth, UInt(32.W))
  val vsrc2Data = Vec(params.laneWidth, UInt(32.W))
}

class VecWData(params: VecParams) extends Bundle {
  val wData = Vec(params.laneWidth, UInt(32.W))
  val wAddr = UInt((params.log2MaxThreadCount + 5).W)
  val htId  = UInt(params.log2MaxThreadCount.W)
  val valid = Bool()
}

// wPort: 非阻塞写口数量（注意：需要经过 Global Scheduler 预约）
// busy: 提供给 global task gen 使用的忙信号，用于同步操作
class VecRegfile(params: VecParams, nbWPortCount: Int, bWPortCount: Int) extends Module {
  val in  = IO(Flipped(Decoupled(new VecInstWThreadInfo(params))))
  val out = IO(Decoupled(new VecInstRData(params)))

  // 存储器例化
  private val mem0 = SyncReadMem(32 * params.maxThreadCount / 2, Vec(params.laneWidth, UInt(32.W)))
  private val mem1 = SyncReadMem(32 * params.maxThreadCount / 2, Vec(params.laneWidth, UInt(32.W)))

  class taggedVInst extends Bundle {
    val task = new VecInstRData(params)
    val valid = Bool()
  }
  private val s1 = RegNext({
    val n = Wire(new taggedVInst)
    PartialAssign(n.task, in.bits)
    n.task.vsrc1Data := DontCare
    n.task.vsrc2Data := DontCare
    n
  })
  private val s2 = RegNext(s1)
  private val s3 = Reg(new taggedVInst)
  s3.valid := s2.valid
  s3.task := s2.task
  out.valid := s3.valid
  out.bits := s3.task

  // Arbiter for Bank0 & Bank1 R Port
  private val vsrc1IsBank0 = s1.valid & s1.task.inst.vsrc1v & !s1.task.inst.realVSrc1(0)
  private val vsrc1IsBank1 = s1.valid & s1.task.inst.vsrc1v &  s1.task.inst.realVSrc1(0)
  private val vsrc2IsBank0 = s2.valid & s2.task.inst.vsrc2v & !s2.task.inst.realVSrc2(0)
  private val vsrc2IsBank1 = s2.valid & s2.task.inst.vsrc2v &  s2.task.inst.realVSrc2(0)

  // No Bank Conflict Check
  chisel3.assert(!(vsrc1IsBank0 & vsrc2IsBank0))
  chisel3.assert(!(vsrc1IsBank1 & vsrc2IsBank1))

  private val bank0RAddr = Mux(vsrc1IsBank0,
    Cat(s1.task.htId, s1.task.inst.realVSrc1(4, 1)), // Read VSRC1
    Cat(s2.task.htId, s2.task.inst.realVSrc2(4, 1))
  )
  private val bank1RAddr = Mux(vsrc1IsBank1,
    Cat(s1.task.htId, s1.task.inst.realVSrc1(4, 1)), // Read VSRC1
    Cat(s2.task.htId, s2.task.inst.realVSrc2(4, 1))
  )
  // TODO: Use Read Enable to save power.
  private val bank0RData = mem0.read(bank0RAddr)
  private val bank1RData = mem1.read(bank1RAddr)
  private val s2VSrc1IsBank0 = RegNext(vsrc1IsBank0)
  private val s3VSrc2IsBank0 = RegNext(vsrc2IsBank0)

  s3.task.vsrc1Data := Mux(s2VSrc1IsBank0, bank0RData, bank1RData)
  out.bits.vsrc2Data := Mux(s3VSrc2IsBank0, bank0RData, bank1RData)

  // Write Port Handling
  // None-Blocking Write Port * N
  val nbWP = IO(Input(Vec(nbWPortCount, new VecWData(params))))
  private val nbWReqBank0 = Mux1H(nbWP.map(i => i.valid & !i.wAddr(0)), nbWP)
  private val nbWReqBank1 = Mux1H(nbWP.map(i => i.valid &  i.wAddr(0)), nbWP)

  // Blockable Write Port(LSU)
  val bWP = IO(Vec(bWPortCount, Flipped(Decoupled(new VecWData(params)))))
  private val bWPBank0s = Wire(Vec(bWPortCount, DecoupledIO(new VecWData(params))))
  private val bWPBank1s = Wire(Vec(bWPortCount, DecoupledIO(new VecWData(params))))
  for(i <- 0 until bWPortCount) {
    bWPBank0s(i).valid       := bWP(i).valid & !bWP(i).bits.wAddr(0)
    bWPBank0s(i).bits.valid  := bWP(i).valid & !bWP(i).bits.wAddr(0)
    bWPBank1s(i).valid       := bWP(i).valid &  bWP(i).bits.wAddr(0)
    bWPBank1s(i).bits.valid  := bWP(i).valid &  bWP(i).bits.wAddr(0)
    bWPBank0s(i).bits := bWP(i).bits
    bWPBank1s(i).bits := bWP(i).bits
    bWP(i).ready := bWPBank0s(i).fire | bWPBank1s(i).fire
  }
  private val bWPBank0Arb = Module(new RRArbiter(new VecWData(params), bWPortCount)).io
  private val bWPBank1Arb = Module(new RRArbiter(new VecWData(params), bWPortCount)).io
  bWPBank0Arb.in <> bWPBank0s
  bWPBank1Arb.in <> bWPBank1s
  bWPBank0Arb.out.ready := !nbWReqBank0.valid
  bWPBank1Arb.out.ready := !nbWReqBank1.valid

  private val wReqBank0 = Mux(nbWReqBank0.valid, nbWReqBank0, bWPBank0Arb.out.bits)
  private val wReqBank1 = Mux(nbWReqBank1.valid, nbWReqBank1, bWPBank1Arb.out.bits)
  when(wReqBank0.valid) {
    mem0.write(wReqBank0.wAddr(4 + params.log2MaxThreadCount, 1), wReqBank0.wData)
  }
  when(wReqBank1.valid) {
    mem1.write(wReqBank1.wAddr(4 + params.log2MaxThreadCount, 1), wReqBank1.wData)
  }

  val busy = IO(Output(Bool()))
  busy := s1.valid | s2.valid | s3.valid

  val varCommit = IO(Output(Vec(2, new VecSlotInfo(params)))) // 可变延迟指令提交
  varCommit(0).valid := bWPBank0Arb.out.fire
  varCommit(0).htId  := bWPBank0Arb.out.bits.htId
  varCommit(1).valid := bWPBank1Arb.out.fire
  varCommit(1).htId  := bWPBank1Arb.out.bits.htId

}
