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

package rvtng.mat

import rvtng.utils.genPipe
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// A: 32 Entry == 1 x RF
// B: 32 Entry == 1 x RF
// C: 64 Entry == 2 x RF
// Input Ctrl: From DPArrayCtrl
// Output Ctrl: To DPArray For Cin
// Input Data: From DPArray or URAM
// Output Data: To DPArray or URAM
// CIN Latency: delay before CIN should be send to DPArray

// Input of C will Convert to inner Floating Point type:
// wE: Inner C Buffer Exponent Width
// wF: Inner C Buffer Fraction Width
// Exp: Signed 9 bits
// Fraction: Signed 25 bits
// Value = 2^(Exp) * (Fraction / 2^(wF-1)
// Output of C will Convert to standard FP32 type
class DPArrayStorageOld(cM: Int, cN: Int, cK: Int, qK: Int, busWidth: Int, cinLatency: Int, wE: Int = 9, wF: Int = 25) extends Module {

  val matValid = IO(Input(Bool()))
  val calcAMatAddr = IO(Input(UInt(6.W)))
  val calcBMatAddr = IO(Input(UInt(6.W)))
  val calcCMatAddr = IO(Input(UInt(6.W)))

  val calcAMatDat = IO(Output(Vec(cM, Vec(cK, UInt(8.W)))))
  val calcAScaleMatDat = IO(Output(Vec(cM, Vec(cK / qK, UInt(8.W)))))
  val calcBMatDat = IO(Output(Vec(cN, Vec(cK, UInt(8.W)))))
  val calcBScaleMatDat = IO(Output(Vec(cN, Vec(cK / qK, UInt(8.W)))))
  val calcCinMatDat  = IO(Output(Vec(cM, Vec(cN, UInt(32.W)))))
  val calcCoutMatDat = IO(Input(Vec(cM, Vec(cN, UInt(32.W)))))

  // Beats that needed to transfer one tile
  val aBeat = (cM * cK + busWidth - 1) / busWidth
  val aScaleBeat = (cM * cK / qK + busWidth - 1) / busWidth
  require(aScaleBeat == 1)
  val bBeat = (cN * cK + busWidth - 1) / busWidth
  val bScaleBeat = (cN * cK / qK + busWidth - 1) / busWidth
  require(bScaleBeat == 1)
  val cBeat = (cM * cN * 4 + busWidth - 1) / busWidth // FP32

  // Core Storage
  // Vec Entry 0 is prepared for mask write.
//  val aMem = Mem(32, Vec(aBeat, Vec(busWidth, UInt(8.W))))
  val aMem = Mem(32, Vec(aBeat * 2, UInt((busWidth * 4).W)))
  require(cM * cK / qK < busWidth)
  require(cN * cK / qK < busWidth)
//  val aScaleMem = Mem(32, Vec(cM * cK / qK, UInt(8.W)))
  val aScaleMem = Mem(32, UInt((8 * cM * cK / qK).W))
//  val bMem = Mem(32, Vec(bBeat, Vec(busWidth, UInt(8.W))))
  val bMem = Mem(32, Vec(bBeat * 2, UInt((busWidth * 4).W)))
//  val bScaleMem = Mem(32, Vec(cN * cK / qK, UInt(8.W)))
  val bScaleMem = Mem(32, UInt((8 * cN * cK / qK).W))
//  val cMem = (0 until 2).map(_ => Mem(32, Vec(cBeat, Vec(busWidth / 4, UInt(32.W)))))
  val cMem = (0 until 2).map(_ => Mem(32, Vec(cBeat, UInt((busWidth * 8).W))))
  // Delay matFwd and cMatAddr for cinLatency
  val pipeMatValid = genPipe(matValid, cinLatency + 2)
  val pipeCMatAddr = genPipe(calcCMatAddr, cinLatency + 2)
  val r2MatValid = pipeMatValid(cinLatency + 2)
  val r0MatValid = pipeMatValid(cinLatency)
  val r2CMatAddr = pipeCMatAddr(cinLatency + 2)
  val r0CMatAddr = pipeCMatAddr(cinLatency)
  val r1CMatAddr = pipeCMatAddr(cinLatency)

  var maxBeat = 0
  if(aBeat > maxBeat) maxBeat = aBeat // aBeat >= aScaleBeat
  if(bBeat > maxBeat) maxBeat = bBeat // bBeat >= bScaleBeat
  if(cBeat > maxBeat) maxBeat = cBeat
  val beatPtrW = log2Ceil(maxBeat)
  print("DPArrayStorage Get Max Beat ", maxBeat)

  // Simplified Situation
  require(aBeat * busWidth == cM * cK)
  require(bBeat * busWidth == cN * cK)

  // External Read/Write Interface for A/B/C - No stalling
  val extMatValid = IO(Input(Bool()))
  val extMatAddr = IO(Input(UInt(5.W)))
  val extMatReqType = IO(Input(DPArrayStorageCmd()))
  val extMatBeatPtr = IO(Input(UInt(beatPtrW.W)))
  val extMatWData = IO(Input(Vec(busWidth, UInt(8.W))))

  val extMatRAddr = IO(Input(UInt(6.W)))
  val extMatRBeatPtr = IO(Input(UInt(log2Ceil(cBeat).W)))
  val extMatRData = IO(Output(Vec(busWidth, UInt(8.W)))) // One Cycle After Issue request

  val s1ExtMatRAddr = RegNext(extMatRAddr)
  val s1ExtMatRBeatPtr = RegNext(extMatRBeatPtr)

  val cMatReadAddr = Wire(Vec(2, UInt(5.W)))
  val cMatWriteAddr = Wire(Vec(2, UInt(5.W)))
  val cMatWriteMask = Wire(Vec(2, Vec(cBeat, Bool())))
  val cMatWriteData = Wire(Vec(2, Vec(cBeat, Vec(busWidth / 4, UInt(32.W)))))
  cMatReadAddr := VecInit.tabulate(2)(i => extMatRAddr(4, 0))
  cMatWriteAddr := VecInit.tabulate(2)(i => extMatAddr(4, 0))
  cMatWriteMask := VecInit.tabulate(2)(i => VecInit.tabulate(cBeat)(round => {
    extMatValid & extMatReqType === DPArrayStorageCmd.loadC & round.U === extMatBeatPtr
  }))
  cMatWriteData := VecInit.tabulate(2)(i => VecInit.tabulate(cBeat)(j => VecInit.tabulate(busWidth / 4)(k =>
    Cat(extMatWData(4 * k + 3),extMatWData(4 * k + 2),extMatWData(4 * k + 1),extMatWData(4 * k + 0)))))
  for(i <- 0 until 2) {
    when(r0MatValid & r0CMatAddr(5) === i.U) {
      cMatReadAddr(i) := r0CMatAddr(4, 0)
    }
    when(r2MatValid & r2CMatAddr(5) === i.U) {
      cMatWriteAddr(i) := r2CMatAddr(4, 0)
      cMatWriteMask(i) := VecInit.tabulate(cBeat)(i => 1.B)
      cMatWriteData(i) := VecInit.tabulate(cBeat, busWidth / 4)((round, idx) => {
        val realIdx = round * busWidth / 4 + idx
        val realM = realIdx / cN
        val realN = realIdx % cN
        calcCoutMatDat(realM)(realN)
      })
    }
  }
  val aReadRaw = RegNext(aMem.read(calcAMatAddr(4, 0)).asTypeOf(Vec(aBeat, Vec(busWidth, UInt(8.W)))))
  aMem.write(extMatAddr(4, 0), VecInit.tabulate(aBeat * 2)
    (i => extMatWData.asUInt(((i % 2) + 1) * busWidth / 2 - 1, (i % 2) * busWidth / 2)),
    VecInit.tabulate(aBeat * 2)(round =>
    extMatValid & extMatReqType === DPArrayStorageCmd.loadA & (round / 2).U === extMatBeatPtr
  ))

  val bReadRaw = RegNext(bMem.read(calcBMatAddr(4, 0)).asTypeOf(Vec(bBeat, Vec(busWidth, UInt(8.W)))))
  bMem.write(extMatAddr(4, 0), VecInit.tabulate(bBeat * 2)
    (i => extMatWData.asUInt(((i % 2) + 1) * busWidth / 2 - 1, (i % 2) * busWidth / 2)),
    VecInit.tabulate(bBeat * 2)(round =>
    extMatValid & extMatReqType === DPArrayStorageCmd.loadB & (round / 2).U === extMatBeatPtr
  ))

  val aScaleReadRaw = RegNext(aScaleMem.read(calcAMatAddr(4, 0)).asTypeOf(Vec(cM * cK / qK, UInt(8.W))))
  when(extMatValid & extMatReqType === DPArrayStorageCmd.loadA) {
    aScaleMem.write(extMatAddr(4, 0), extMatWData.asUInt)
  }

  val bScaleReadRaw = RegNext(bScaleMem.read(calcBMatAddr(4, 0)).asTypeOf(Vec(cN * cK / qK, UInt(8.W))))
  when(extMatValid & extMatReqType === DPArrayStorageCmd.loadB) {
    bScaleMem.write(extMatAddr(4, 0), extMatWData.asUInt)
  }

  val cReadRaw = VecInit.tabulate(2)(i => {
    RegNext(cMem(i).read(cMatReadAddr(i))).asTypeOf(Vec(cBeat, Vec(busWidth / 4, UInt(32.W))))
  }) // No Output Register on C to mitigate latency
  val cReadMemView = VecInit.tabulate(2, cBeat, busWidth)((i, round, idx) => {
    cReadRaw(i)(round)(idx / 4)(8 * (idx % 4) + 7, 8 * (idx % 4))
  })
  extMatRData := cReadMemView(s1ExtMatRAddr(5))(s1ExtMatRBeatPtr)
  (0 until 2).map(i => {
    cMem(i).write(cMatWriteAddr(i), VecInit.tabulate(cBeat)(j => cMatWriteData(i).asUInt), cMatWriteMask(i))
    Nil
  })

  // Internal Write Interface from DPArray
  calcAMatDat := VecInit.tabulate(cM, cK)((m, k) => {
    val idx = m * cK + k
    val idxHi = idx / busWidth
    val idxLo = idx % busWidth
    aReadRaw(idxHi)(idxLo)
  })
  calcAScaleMatDat := VecInit.tabulate(cM, cK/qK)((m, k) => {
    val idx = m * cK / qK + k
    aScaleReadRaw(idx)
  })
  calcBMatDat := VecInit.tabulate(cN, cK)((n, k) => {
    val idx = n * cK + k
    val idxHi = idx / busWidth
    val idxLo = idx % busWidth
    bReadRaw(idxHi)(idxLo)
  })
  calcBScaleMatDat := VecInit.tabulate(cN, cK/qK)((n, k) => {
    val idx = n * cK / qK + k
    bScaleReadRaw(idx)
  })
  calcCinMatDat := VecInit.tabulate(cM, cN)((m, n) => {
    val idx = m * cN + n
    val idxHi = idx / (busWidth / 4)
    val idxLo = idx % (busWidth / 4)
    cReadRaw(r1CMatAddr(5))(idxHi)(idxLo)
  })
}