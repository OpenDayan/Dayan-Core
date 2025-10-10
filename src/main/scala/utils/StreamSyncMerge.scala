package utils

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// This Module is used to merge multiple Stream to one Stream
// All Input Port should be fired or denied in the same cycle.
class StreamSyncMergeSpilt[ReqT <: Data, RespT <: Data](
    ports: Int, maxLatency: Int,
    reqGen: ReqT, respGen: RespT) extends Module {
  val req_i = IO(Flipped(Vec(ports, Decoupled(reqGen))))
  val req_o = IO(Decoupled(reqGen))
  val iReqValid = Wire(Bool())
  val iReqReady = Wire(Bool())
  val iReqFire  = iReqValid & iReqReady;
  val iReqValidMap = VecInit(req_i.map(_.valid))
  val iReqPayloadMap = VecInit(req_i.map(_.bits))
  req_i.map(_.ready := iReqReady)
  iReqValid := iReqValidMap.reduceTree(_ | _)

  val oReqValid = Wire(Bool())
  val oReqReady = Wire(Bool())
  val oReqFire  = oReqValid & oReqReady

  // Payload and Valid Register
  val mergeValidMap   = RegInit(VecInit.tabulate(ports)(i => 0.B))
  val mergeValidMapOH = PriorityEncoderOH(mergeValidMap.asUInt)
  val mergePayloadMap = RegEnable(iReqPayloadMap, iReqFire)
  val mergeValid      = RegInit(Bool(), 0.B)
  val mergeLast       = mergeValidMapOH === mergeValidMap.asUInt
  when(iReqFire) {
    mergeValid    := 1.B
    mergeValidMap := iReqValidMap
  }.elsewhen(oReqFire) {
    mergeValid    := !mergeLast
    mergeValidMap :=
      (mergeValidMap.asUInt & (~mergeValidMapOH).asUInt).asTypeOf(mergeValidMap)
  }
  // Arbiter
  val reqQ = Module(new Queue(reqGen, 1, false, true)) // Skid Buffer Entry
  reqQ.io.enq.bits := PriorityMux(mergeValidMap, mergePayloadMap)
  reqQ.io.enq.valid := oReqValid
  oReqReady := reqQ.io.enq.ready
  req_o <> reqQ.io.deq
}
