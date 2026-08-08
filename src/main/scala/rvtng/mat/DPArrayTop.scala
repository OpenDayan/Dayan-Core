/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package dayan.rvtng.mat

import dayan.rvtng.uram.{AdvURAMReq, AdvURAMResp, AdvURAMWResp, SwizzleAddr}
import dayan.rvtng.utils.genPipe
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import dayan.rvtng.fpmath.DPArray.{FP8BF16FusedDPArray, U8m2n2k1U4m2n2k2Mul}

object DPArrayStorageCmd extends ChiselEnum {
  val loadA, loadB, loadC = Value
}

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 16 >= MK 8 2
// B: 16 >= NK 4 2
// C: 32 >= MN 8 4
class MatCalcCommand(storageRatio: Int) extends Bundle {
  // Compute Mode
  val bf16 = Bool()
  val aBank = Bool()
  val bBank = Bool()
  val cBank = Bool()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SM = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SN = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SK = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pM = UInt(log2Ceil(storageRatio).W) // pM <= sM
  val pN = UInt(log2Ceil(storageRatio).W) // pN <= sN
  val pK = UInt(log2Ceil(storageRatio).W) // pK <= sK
}


class MatLoadStoreCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  // Compute Mode
  val bank = Bool()
  val ctrl = DPArrayStorageCmd()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SDimMajor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SDimMinor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pMajor = UInt(log2Ceil(storageRatio * cMNK).W) // pM / pN
  val pMinor = UInt(log2Ceil(storageRatio * cMNK).W) //      pK

  // Address Information
  val baseAddr     = UInt(addressWidth.W)
  val majorStride  = UInt(addressWidth.W) // in Bytes
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
  val SwizzleMBS   = UInt(24.W)
}

class DPArrayTop(
 storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int,
 cM: Int, cN: Int, cK: Int, sK: Int, idWidth: Int,
 wE: Int = 9, wF: Int = 25, outStanding: Int = 16) extends Module {
  require(cN * 4 == cK)
  val cMNK = Array(cM, cN, cK).max
  val maxTileSize = Array(cM * cK, cN * cK, cM * cN * 4).max
  val maxBeat = maxTileSize / busWidth
  val ports = busWidth / 8

  // Load/Store Cmd Interface
  val ldACmd    = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val ldBCmd    = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val ldCCmd    = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val ldBar     = IO(Flipped(Decoupled(Vec(3, Bool())))) // Ld Barrier
  val stCmd     = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))

  // Calculate  Cmd Interface
  val calcCmd = IO(Flipped(Decoupled(new MatCalcCommand(storageRatio))))

  // Completion Resp Interface
  val ldComp = IO(Decoupled(Bool()))
  val crComp = IO(Decoupled(Bool()))
  val cwComp = IO(Decoupled(Bool()))
  val stComp = IO(Decoupled(Bool()))

  // URAM Interface
  val channelCount    = busWidth / cK
  val portsPerChannel = ports    / channelCount
  val rreq0  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val rreq1  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val wreq   = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val wdata  = IO(Output(Vec(ports,        UInt(64.W))))

  val rresp0 = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMResp(addressWidth,  0, 64))))))
  val rresp1 = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMResp(addressWidth,  0, 64))))))
  val wresp  = IO(Flipped(Vec(channelCount, Decoupled(Vec(portsPerChannel, new AdvURAMWResp(addressWidth, 0, 64))))))

  // Non-Blocking calculating array
  val calcCore = Module(new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
    M = cM, N = cN, K = cK, sK = sK,
    debugFP32Output = true, enableStickBit = false, wMul = 17
  ))

  // Tensor Buffer
  val aBuffer = Module(new DPArrayBufferAB(busWidth, storageRatio * 2, cM, cK))
  val bBuffer = Module(new DPArrayBufferAB(busWidth, storageRatio * 2, cN, cK))
  val cBuffer = Module(new DPArrayBufferC (busWidth, storageRatio, cM, cN, wE, wF))

  val abBankSft = log2Ceil(storageRatio)
  val cBankSft  = log2Ceil(storageRatio)
  // DPArray Calculating Task Spilt
  val calcTaskSpilt = Module(new DPArrayCalcTaskSpilt(storageRatio, calcCore.r0Latency,
    abBankSft, abBankSft, cBankSft))

  calcTaskSpilt.cmdIn :<>= calcCmd
  crComp :<>= calcTaskSpilt.crComp
  cwComp :<>= calcTaskSpilt.cwComp

  val calcCMatAddrPipe = genPipe(calcTaskSpilt.cmdOut.bits.cMatAddr, calcCore.r0Latency + 3)
  val calcCMatValidPipe = genPipe(calcTaskSpilt.cmdOut.valid, calcCore.r0Latency + 3)

  calcTaskSpilt.cmdOut.ready := 1.B
  aBuffer.rAddr := calcTaskSpilt.cmdOut.bits.aMatAddr
  bBuffer.rAddr := calcTaskSpilt.cmdOut.bits.bMatAddr
  cBuffer.cRAddr := calcCMatAddrPipe(calcCore.r0Latency)
  cBuffer.cRe := calcCMatValidPipe(calcCore.r0Latency)

  calcCore.valid := RegNext(calcTaskSpilt.cmdOut.valid)
  calcCore.bf16 := RegNext(calcTaskSpilt.cmdOut.bits.bf16)
  calcCore.a := aBuffer.rData
  calcCore.b := bBuffer.rData
  // TODO: Add A and B Scale buffer
  calcCore.a_scale := 0.U.asTypeOf(calcCore.a_scale)
  calcCore.b_scale := 0.U.asTypeOf(calcCore.b_scale)
  calcCore.r0_cin_exp_in := VecInit.tabulate(cM, cN)((m, n) => {
    cBuffer.cRData(m)(n)(wF + wE - 1, wF).asSInt
  })

  calcCore.r0_cin_frac_in := VecInit.tabulate(cM, cN)((m, n) => {
    cBuffer.cRData(m)(n)(wF - 1, 0).asSInt
  })

  cBuffer.cWData := VecInit.tabulate(cM, cN)((m, n) => {
    Cat(calcCore.cout_exp(m)(n), calcCore.cout_frac(m)(n))
  })
  cBuffer.cWAddr := calcCMatAddrPipe(calcCore.r0Latency + 3)
  cBuffer.cWe := calcCMatValidPipe(calcCore.r0Latency + 3)

  // Connecting wPort / rPort of all buffer to uram
  val flattenUramRData0 = VecInit(rresp0.map(c => {
    VecInit(c.bits.map(_.data))
  })).asTypeOf(Vec(busWidth, UInt(8.W)))
  val flattenUramRData1 = VecInit(rresp1.map(c => {
    VecInit(c.bits.map(_.data))
  })).asTypeOf(Vec(busWidth, UInt(8.W)))
  aBuffer.wData    := flattenUramRData1
  bBuffer.wData    := flattenUramRData0
  cBuffer.extWData := flattenUramRData1
  wdata := cBuffer.extRData.asTypeOf(wdata)

  // Load Store Task Spilt Unit

  // A Load Task Spilt Unit
  val aLoadTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, aTask = true, bTask = false ,cTask = false, storeTask = false, abBankSft, idWidth
  ))
  // B Load Task Spilt Unit
  val bLoadTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, aTask = false, bTask = true, cTask = false, storeTask = false, abBankSft, idWidth
  ))
  // C Load Task Spilt Unit
  val cLoadTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, aTask = false, bTask = false, cTask = true, storeTask = false, cBankSft, idWidth
  ))
  // C Store Task Spilt Unit
  val cStoreTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, aTask = false, bTask = false, cTask = true, storeTask = true, cBankSft, idWidth
  ))
  aLoadTaskSpilter.cmdIn  :<>= ldACmd
  bLoadTaskSpilter.cmdIn  :<>= ldBCmd
  cLoadTaskSpilter.cmdIn  :<>= ldCCmd
  cStoreTaskSpilter.cmdIn :<>= stCmd

  // URAM RP0: MAT.B,  VEC.L
  // URAM RP1: MAT.AC, LSU.L
  // URAM WP0: MAT.C,  VEC.S, LSU.S

  // RP1 Arbiter
  //  val uramRP1Arb = Module(new RRArbiter(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth,
  //    addressWidth, cMNK, idWidth), 2))
  //  uramRP1Arb.io.in <> VecInit(Seq(aLoadTaskSpilter.cmdURAM, cLoadTaskSpilter.cmdURAM))
  //  val tileRP1ArbRdy = Wire(Bool())
  //  val tileRP1ArbVld = VecInit(Seq(aLoadTaskSpilter.cmdTile.valid, cLoadTaskSpilter.cmdTile.valid))(uramRP1Arb.io.chosen)
  //  val tileRP1Arb    = VecInit(Seq(aLoadTaskSpilter.cmdTile.bits,  cLoadTaskSpilter.cmdTile.bits))(uramRP1Arb.io.chosen)
  //  val tileRP1ArbFire = tileRP1ArbVld & tileRP1ArbRdy
  //  aLoadTaskSpilter.cmdTile.ready := tileRP1ArbRdy & (uramRP1Arb.io.chosen === 0.U)
  //  cLoadTaskSpilter.cmdTile.ready := tileRP1ArbRdy & (uramRP1Arb.io.chosen === 1.U)

  // Bug Note:
  //   RP1 Arbiter may lead to deadlock, move it backward.

  // Barrier signals
  val aLdCompChannel  = Wire(Vec(channelCount, Bool()))
  val bLdCompChannel  = Wire(Vec(channelCount, Bool()))
  val cLdCompChannel  = Wire(Vec(channelCount, Bool()))
  val cStCompChannel  = Wire(Vec(channelCount, Bool()))
  val aLdCompChannelR = aLdCompChannel.asUInt.andR
  val bLdCompChannelR = bLdCompChannel.asUInt.andR
  val cLdCompChannelR = cLdCompChannel.asUInt.andR
  val cStCompChannelR = cStCompChannel.asUInt.andR
  val aLdCompReady    = Wire(Bool())
  val bLdCompReady    = Wire(Bool())
  val cLdCompReady    = Wire(Bool())
  val cStCompReady    = Wire(Bool())

  // Split Uram Cmd to Separate Buffer.
  val uramRP0QReady  = Wire(Vec(channelCount, Bool()))
  val uramRP1AQReady = Wire(Vec(channelCount, Bool()))
  val uramRP1CQReady = Wire(Vec(channelCount, Bool()))
  val uramWPQReady   = Wire(Vec(channelCount, Bool()))
  val tileRP0QReady  = Wire(Vec(channelCount, Bool()))
  val tileRP1AQReady = Wire(Vec(channelCount, Bool()))
  val tileRP1CQReady = Wire(Vec(channelCount, Bool()))
  val tileWPQReady   = Wire(Vec(channelCount, Bool()))
  aLoadTaskSpilter.cmdURAM.ready  := uramRP1AQReady.asUInt.andR
  aLoadTaskSpilter.cmdTile.ready  := tileRP1AQReady.asUInt.andR
  bLoadTaskSpilter.cmdURAM.ready  := uramRP0QReady.asUInt.andR
  bLoadTaskSpilter.cmdTile.ready  := tileRP0QReady.asUInt.andR
  cLoadTaskSpilter.cmdURAM.ready  := uramRP1CQReady.asUInt.andR
  cLoadTaskSpilter.cmdTile.ready  := tileRP1CQReady.asUInt.andR
  cStoreTaskSpilter.cmdURAM.ready := uramWPQReady.asUInt.andR
  cStoreTaskSpilter.cmdTile.ready := tileWPQReady.asUInt.andR
  for(channel <- 0 until channelCount) {
    // WP
    val uramWPQ = Module(new Queue(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth,
      addressWidth, cMNK, idWidth), outStanding, false, false))
    uramWPQReady(channel) := uramWPQ.io.enq.ready
    uramWPQ.io.enq.valid  := cStoreTaskSpilter.cmdURAM.fire
    uramWPQ.io.enq.bits   := cStoreTaskSpilter.cmdURAM.bits

    val tileWPQ = Module(new Queue(
      new DPALoadStoreTileMicroCommand(storageRatio, maxBeat, idWidth), outStanding, false, false))
    tileWPQReady(channel) := tileWPQ.io.enq.ready
    tileWPQ.io.enq.valid   := cStoreTaskSpilter.cmdTile.fire
    tileWPQ.io.enq.bits    := cStoreTaskSpilter.cmdTile.bits

    val tileIsLastWPQ = Module(new Queue(Bool(), outStanding, false, false))
    tileIsLastWPQ.io.enq.valid := tileWPQ.io.deq.fire
    tileIsLastWPQ.io.enq.bits  := tileWPQ.io.deq.bits.last
    tileIsLastWPQ.io.deq.ready := wresp(channel).valid

    // TODO: 4 maybe too small?
    val compWPQ = Module(new Queue(Bool(), 4, false, false))
    compWPQ.io.enq.valid := wresp(channel).valid & tileIsLastWPQ.io.deq.bits
    compWPQ.io.enq.bits  := 0.B
    cStCompChannel(channel) := compWPQ.io.deq.valid
    compWPQ.io.deq.ready := cStCompReady
    chisel3.assert(!(compWPQ.io.enq.valid & !compWPQ.io.enq.ready))

    // Two-stage rdata handling
    val s1RAddr = tileWPQ.io.deq.bits.tileAddr
    val s1RBeat = tileWPQ.io.deq.bits.beat
    val s2RAddr = RegEnable(s1RAddr, tileWPQ.io.deq.fire)
    val s2RBeat = RegEnable(s1RBeat, tileWPQ.io.deq.fire)
    val s2Valid = RegInit(0.B)
    when(wreq(channel * portsPerChannel).fire | !s2Valid) {
      s2Valid := tileWPQ.io.deq.valid
    }
    val s2Deny = s2Valid & !wreq(channel * portsPerChannel).fire
    tileWPQ.io.deq.ready := wreq(channel * portsPerChannel).fire | !s2Valid
    cBuffer.extRe(channel) := s2Deny | tileWPQ.io.deq.valid
    cBuffer.extRAddr(channel) := Mux(s2Deny, s2RAddr, s1RAddr)
    cBuffer.extRBeat(channel) := Mux(s2Deny, s2RBeat, s1RBeat)

    (0 until portsPerChannel).foreach(p => {
      // Get Mapped
      val fixedMajor = channel
      val fixedMinorByte = 8 * p
      val major = fixedMajor.U         + uramWPQ.io.deq.bits.baseDimMajor
      val minorByte = fixedMinorByte.U + uramWPQ.io.deq.bits.baseDimMinorByte
      // 对应偏移
      val addr = major * uramWPQ.io.deq.bits.majorStride + minorByte + uramWPQ.io.deq.bits.baseUramAddr
      // TODO: Swizzling 逻辑
      // TODO: Timing! addr should be latched before send to uram request bus.
      // 开始连线
      wreq(channel * portsPerChannel + p).bits.id := 0.U
      wreq(channel * portsPerChannel + p).bits.address := SwizzleAddr(addr,
        uramWPQ.io.deq.bits.swizzleS, uramWPQ.io.deq.bits.swizzleB, uramWPQ.io.deq.bits.swizzleM) >> 3
      wreq(channel * portsPerChannel + p).valid := uramWPQ.io.deq.valid & s2Valid
    })
    uramWPQ.io.deq.ready := wreq(channel * portsPerChannel).fire & s2Valid
    wresp(channel).ready := tileIsLastWPQ.io.deq.valid

    // RP0
    val uramRP0Q = Module(new Queue(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth,
      addressWidth, cMNK, idWidth), outStanding, false, false))
    uramRP0QReady(channel) := uramRP0Q.io.enq.ready
    uramRP0Q.io.enq.valid  := bLoadTaskSpilter.cmdURAM.fire
    uramRP0Q.io.enq.bits   := bLoadTaskSpilter.cmdURAM.bits

    val tileRP0Q = Module(new Queue(
      new DPALoadStoreTileMicroCommand(storageRatio, maxBeat, idWidth), outStanding, false, false))
    tileRP0QReady(channel) := tileRP0Q.io.enq.ready
    tileRP0Q.io.enq.valid   := bLoadTaskSpilter.cmdTile.fire
    tileRP0Q.io.enq.bits    := bLoadTaskSpilter.cmdTile.bits

    // TODO: 4 maybe too huge or too small?
    val compRP0Q = Module(new Queue(Bool(), 4, false, false))
    compRP0Q.io.enq.valid := rresp0(channel).valid & tileRP0Q.io.deq.bits.last
    compRP0Q.io.enq.bits  := 0.B
    bLdCompChannel(channel) := compRP0Q.io.deq.valid
    compRP0Q.io.deq.ready := bLdCompReady
    chisel3.assert(!(compRP0Q.io.enq.valid & !compRP0Q.io.enq.ready))

    (0 until portsPerChannel).foreach(p => {
      // Get Mapped
      val fixedMajor = channel
      val fixedMinorByte = 8 * p
      val major = fixedMajor.U         + uramRP0Q.io.deq.bits.baseDimMajor
      val minorByte = fixedMinorByte.U + uramRP0Q.io.deq.bits.baseDimMinorByte
      // 对应偏移
      val addr = major * uramRP0Q.io.deq.bits.majorStride + minorByte + uramRP0Q.io.deq.bits.baseUramAddr
      // TODO: Swizzling 逻辑
      // TODO: Timing! addr should be latched before send to uram request bus.
      // 开始连线
      rreq0(channel * portsPerChannel + p).bits.id := 0.U
      rreq0(channel * portsPerChannel + p).bits.address := SwizzleAddr(addr,
        uramRP0Q.io.deq.bits.swizzleS, uramRP0Q.io.deq.bits.swizzleB, uramRP0Q.io.deq.bits.swizzleM) >> 3
      rreq0(channel * portsPerChannel + p).valid := uramRP0Q.io.deq.valid
    })
    bBuffer.wAddr(channel) := tileRP0Q.io.deq.bits.tileAddr
    bBuffer.wBeat(channel) := tileRP0Q.io.deq.bits.beat
    bBuffer.we(channel) := tileRP0Q.io.deq.valid /* & tileRP0Q.io.deq.bits.isBLoad */ & rresp0(channel).valid
    tileRP0Q.io.deq.ready := rresp0(channel).valid
    uramRP0Q.io.deq.ready := rreq0(channel * portsPerChannel).ready
    rresp0(channel).ready := tileRP0Q.io.deq.valid

    // RP1
    val uramRP1AQ = Module(new Queue(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth,
      addressWidth, cMNK, idWidth), outStanding, false, false))
    uramRP1AQReady(channel) := uramRP1AQ.io.enq.ready
    uramRP1AQ.io.enq.valid  := aLoadTaskSpilter.cmdURAM.fire
    uramRP1AQ.io.enq.bits   := aLoadTaskSpilter.cmdURAM.bits

    val tileRP1AQ = Module(new Queue(
      new DPALoadStoreTileMicroCommand(storageRatio, maxBeat, idWidth), outStanding, false, false))
    tileRP1AQReady(channel)  := tileRP1AQ.io.enq.ready
    tileRP1AQ.io.enq.valid   := aLoadTaskSpilter.cmdTile.fire
    tileRP1AQ.io.enq.bits    := aLoadTaskSpilter.cmdTile.bits

    val uramRP1CQ = Module(new Queue(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth,
      addressWidth, cMNK, idWidth), outStanding, false, false))
    uramRP1CQReady(channel) := uramRP1CQ.io.enq.ready
    uramRP1CQ.io.enq.valid  := cLoadTaskSpilter.cmdURAM.fire
    uramRP1CQ.io.enq.bits   := cLoadTaskSpilter.cmdURAM.bits

    val tileRP1CQ = Module(new Queue(
      new DPALoadStoreTileMicroCommand(storageRatio, maxBeat, idWidth), outStanding, false, false))
    tileRP1CQReady(channel)  := tileRP1CQ.io.enq.ready
    tileRP1CQ.io.enq.valid   := cLoadTaskSpilter.cmdTile.fire
    tileRP1CQ.io.enq.bits    := cLoadTaskSpilter.cmdTile.bits

    // TODO: 4 maybe too huge or too small?
    val compRP1AQ = Module(new Queue(Bool(), 4, false, false))
    compRP1AQ.io.enq.valid := tileRP1AQ.io.deq.fire & tileRP1AQ.io.deq.bits.last
    compRP1AQ.io.enq.bits  := 0.B
    aLdCompChannel(channel) := compRP1AQ.io.deq.valid
    compRP1AQ.io.deq.ready := aLdCompReady
    chisel3.assert(!(compRP1AQ.io.enq.valid & !compRP1AQ.io.enq.ready))
    val compRP1CQ = Module(new Queue(Bool(), 4, false, false))
    compRP1CQ.io.enq.valid := tileRP1CQ.io.deq.fire & tileRP1CQ.io.deq.bits.last
    compRP1CQ.io.enq.bits  := 0.B
    cLdCompChannel(channel) := compRP1CQ.io.deq.valid
    compRP1CQ.io.deq.ready := cLdCompReady
    chisel3.assert(!(compRP1CQ.io.enq.valid & !compRP1CQ.io.enq.ready))

    val RP1Arb = Module(new Arbiter(new DPALoadStoreURAMMicroCommand(storageRatio, mnkWidth,
      addressWidth, cMNK, idWidth), 2))
    RP1Arb.io.in(0) <> uramRP1AQ.io.deq
    RP1Arb.io.in(1) <> uramRP1CQ.io.deq
    val RP1SelQ = Module(new Queue(UInt(1.W), 2 * outStanding, false, false))
    RP1SelQ.io.enq.valid := RP1Arb.io.out.fire
    RP1SelQ.io.enq.bits  := RP1Arb.io.chosen

    (0 until portsPerChannel).foreach(p => {
      // Get Mapped
      val fixedMajor = channel
      val fixedMinorByte = 8 * p
      val major     = fixedMajor.U     + RP1Arb.io.out.bits.baseDimMajor
      val minorByte = fixedMinorByte.U + RP1Arb.io.out.bits.baseDimMinorByte
      // 对应偏移
      val addr = major * RP1Arb.io.out.bits.majorStride + minorByte + RP1Arb.io.out.bits.baseUramAddr
      // TODO: Swizzling 逻辑
      // TODO: Timing! addr should be latched before send to uram request bus.
      // 开始连线
      rreq1(channel * portsPerChannel + p).bits.id := 0.U
      rreq1(channel * portsPerChannel + p).bits.address := SwizzleAddr(addr,
        RP1Arb.io.out.bits.swizzleS, RP1Arb.io.out.bits.swizzleB, RP1Arb.io.out.bits.swizzleM) >> 3
      rreq1(channel * portsPerChannel + p).valid := RP1Arb.io.out.valid
    })
    RP1Arb.io.out.ready    := rreq1(channel * portsPerChannel).ready
    aBuffer.wAddr(channel) := tileRP1AQ.io.deq.bits.tileAddr
    aBuffer.wBeat(channel) := tileRP1AQ.io.deq.bits.beat
    aBuffer.we(channel)    := tileRP1AQ.io.deq.fire
    cBuffer.extWAddr(channel) := tileRP1CQ.io.deq.bits.tileAddr
    cBuffer.extWBeat(channel) := tileRP1CQ.io.deq.bits.beat
    cBuffer.extWe(channel)    := tileRP1CQ.io.deq.fire
    tileRP1AQ.io.deq.ready := rresp1(channel).valid & (RP1SelQ.io.deq.bits === 0.U)
    tileRP1CQ.io.deq.ready := rresp1(channel).valid & (RP1SelQ.io.deq.bits === 1.U)
    RP1SelQ.io.deq.ready   := rresp1(channel).fire
    rresp1(channel).ready  := (tileRP1CQ.io.deq.valid & (RP1SelQ.io.deq.bits === 1.U)) |
                              (tileRP1AQ.io.deq.valid & (RP1SelQ.io.deq.bits === 0.U))
  }

  // Load Bar
  val loadBar = Queue(ldBar, 8, false, false)

  // load Completion Handling
  //  ldComp.valid := loadABCompQ.io.deq.valid & (!loadABCompQ.io.deq.bits | loadCCompQ.io.deq.valid)
  ldComp.bits := 0.B
  ldComp.valid := loadBar.valid & loadBar.bits.asUInt ===
    (loadBar.bits.asUInt & Cat(cLdCompChannelR, bLdCompChannelR, aLdCompChannelR))
  aLdCompReady := ldComp.valid & loadBar.bits(0)
  bLdCompReady := ldComp.valid & loadBar.bits(1)
  cLdCompReady := ldComp.valid & loadBar.bits(2)
  loadBar.ready := ldComp.valid

  // store Completion Handling
  //  stComp.valid := wreq(0).ready & s2LastLoad & s2Valid
  stComp.bits := 0.B
  stComp.valid := cStCompChannelR
  cStCompReady := stComp.valid
}

object DPArrayTop extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayTop(32, 16, 20, 20, 4, 2, 8, 8, 5),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayTop"
    )
  )
}

