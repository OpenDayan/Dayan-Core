package rvtng.vec

import chisel3._
import chisel3.util._
import rvtng.utils._

class VecInstWThreadInfo(params: VecParams) extends Bundle {
  val inst = new VecInstWSchedule(params)
  val htId = UInt(params.maxThreadCount.W)
  val pc  = UInt(params.log2MaxInstCount.W)
}

class VecSlotInfo(params: VecParams) extends Bundle {
  val htId = UInt(params.maxThreadCount.W)
  val valid = Bool()
}

/*
* 需求：
*    记录单个向量的执行状态，包含 Dim1、Dim0、V基地址 * 4、S基地址 * 4、Stall Counter、Var Latency Bar、CurOffset、Block。
*
* 接口：
*    - allocateReq: 流式任务分派接口，接收一个分派来的执行流
*    - busy: 输出，表示当前执行线程正忙
*    - fetchReq: 流式取指请求接口，从 InstMem 请求取指令
*    - fetchResp: 广播取指响应，从 InstMem 返回的指令结果
*    - preIssue: 流式预发射接口，发射到调度器一级
*    - Issue: 广播发射接口，来自调度器，广播实际被选择发射的指令
*    - varCommit: 脉冲广播输入接口，来自执行单元，广播被提交的不定延迟指令
*
* 实现：
*    内部记录当前线程状态：空闲（无执行流）、取指（无指令等待取指）、等待发射（已完成取指，等待数据就绪后发射）、预发射0（指令上周期完成预发射）、
* 预发射1（指令两周期前完成预发射）
*
* */

class VecThreadCtrl(params: VecParams, htId: Int) extends Module {
  val allocateReq = IO(Flipped(Decoupled(new VecInitTaskGroup(params))))
  val busy = IO(Output(Bool()))
  val fetchReq = IO(Decoupled(UInt(params.log2MaxInstCount.W))) // 取指令偏移
  val fetchResp = IO(Input(new VecInstFetchResp(params)))
  val preIssue = IO(Decoupled(new VecInstWThreadInfo(params)))
  val preIssueResp = IO(Input(new VecSlotInfo(params)))
  val varCommit = IO(Input(Vec(2, new VecSlotInfo(params)))) // 可变延迟指令提交, 2 Bank

  /// 关键寄存器
  private val pc = Reg(UInt(params.log2MaxInstCount.W))
  private val task = Reg(new VecInitTaskGroup(params))
  // TODO: Enable Bank Swizzling in different Threads.
  preIssue.bits.htId := htId.U
  preIssue.bits.pc := pc // For better schedule logic

  private val curFetched = fetchResp.valid & fetchResp.pc === pc
  private val curFetchFinished = fetchResp.inst.finishHint
  private val curIssued = preIssueResp.valid & preIssueResp.htId === htId.U

  private object S extends ChiselEnum {
    val sFree, sFetch, sWait, sIssue, sPreIssue0, sPreIssue1 = Value
  }
  private val state = RegInit(S.sFree)

  // 依赖处理逻辑
  private val stallCounter = Reg(UInt(params.log2MaxLatency.W)) // Stalling Counter
  private val waitVarLat = Reg(Bool())
  private val varBar = RegInit(0.B) // flex-Latency Instruction Execute Barrier

  // 不可变延迟 Counter 更新逻辑
  when(stallCounter =/= 0.U) {
    stallCounter := stallCounter - 1.U
  }

  // 可变延迟 Barrier 更新逻辑
  varBar := (varBar & !(
    (varCommit(0).valid && varCommit(0).htId === htId.U) | (varCommit(1).valid && varCommit(1).htId === htId.U)
    )) | (state === S.sPreIssue1 & curIssued & (task.inst.optionalLatency === 0.U))

  allocateReq.ready := state === S.sFree
  busy := state =/= S.sFree
  fetchReq.valid := (state === S.sFetch) & RegNext(!fetchReq.fire)
  fetchReq.bits := pc
  preIssue.valid := state === S.sIssue
  preIssue.bits.htId := htId.U
  preIssue.bits.inst := task.inst
  // TODO: More address calculation here.
  when(task.inst.fu === VecFuEnum.Lsu) {
    // v load/store
    when(task.inst.optype(4, 0) === 0b00000.U) {
      preIssue.bits.inst.rs2Data := task.inst.rs2Data + task.vbase(task.inst.vsrc1(3, 2))
    }

    // s load/store
    when(task.inst.optype(4, 0) === 0b00001.U) {
      preIssue.bits.inst.rs2Data := task.inst.rs2Data + task.sbase(task.inst.vsrc1(3, 2))
    }
  }
  //  preIssue.bits.inst.realVDst := task.inst.realVDst ^ htId.U(5.W)(0)
  //  preIssue.bits.inst.realVSrc1 := task.inst.realVSrc1 ^ htId.U(5.W)(0)
  //  preIssue.bits.inst.realVSrc2 := task.inst.realVSrc2 ^ htId.U(5.W)(0)

  switch (state) {
    is(S.sFree) {
      when (allocateReq.fire) {
        // Init PC
        pc := 0.U
        task := allocateReq.bits
        state := S.sIssue
      }
    }
    is(S.sWait) {
      when(!((waitVarLat & varBar) | stallCounter =/= 0.U)) {
        state := S.sPreIssue0
      }
    }
    is(S.sIssue) {
      when (preIssue.fire) {
        state := S.sPreIssue0
      }
    }
    is(S.sPreIssue0) {
      state := S.sPreIssue1
    }
    is(S.sPreIssue1) {
      when (curIssued) {
        state := S.sFetch
        // Update PC
        pc := pc + 1.U
      }.otherwise {
        state := S.sIssue
      }
    }
    is(S.sFetch) {
      when (curFetched) {
        when(curFetchFinished) {
          state := S.sFree
        }.otherwise {
          state := S.sWait
        }
        task.inst := fetchResp.inst
        stallCounter := fetchResp.inst.waitCycle
        waitVarLat := fetchResp.inst.waitVarLat
      }
    }
  }
}
