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

package dayan.rvtng.top

import chisel3._
import chisel3.util._

class RVTNGInstDispatch extends Module {

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))

  val matReq = IO(Decoupled(new TensorInst))
  val matResp = IO(Flipped(Decoupled(new TensorResp)))
  val vecReq = IO(Decoupled(new TensorInst))
  val vecResp = IO(Flipped(Decoupled(new TensorResp)))
  val dmaReq = IO(Decoupled(new TensorInst))
  val dmaResp = IO(Flipped(Decoupled(new TensorResp)))
  val sbReq = IO(Decoupled(new TensorInst))
  val sbResp = IO(Flipped(Decoupled(new TensorResp)))
  val vpuReq = IO(Decoupled(new TensorInst))
  val vpuResp = IO(Flipped(Decoupled(new TensorResp)))

  private val instQ = Queue(instReq, 2)

  // Decoder Logic here
  private val inst = instQ.bits.instWord
  private val decodeIsTCSRorScoreboard = inst(31, 28) === 0.U

  private val decodeIs64B = inst(6, 0) === "h3f".U
  private val decodeIs32B = inst(6, 0) === "h0b".U
  private val decodeIsVecScoreboard = decodeIs32B & (inst(31, 27) === 0b00001.U) & (inst(26, 25) === 1.U) &
                                      (inst(24, 15) === 0.U) & ((inst(14, 12) === 0.U) | (inst(14, 12) === 5.U))

  private val decodeIsScoreboard = decodeIsTCSRorScoreboard & inst(26, 25) === 3.U & decodeIs32B
  private val decodeIsMat = ((decodeIsTCSRorScoreboard & inst(26, 25) === 0.U) | (inst(31, 28) === 1.U)) & decodeIs32B
  private val decodeIsDma = ((decodeIsTCSRorScoreboard & inst(26, 25) === 2.U) | (inst(31, 29) === 1.U)) & decodeIs32B
  private val decodeIsVec = (((decodeIsTCSRorScoreboard & inst(26, 25) === 1.U) | (inst(31, 30) > 0.U))   & decodeIs32B) &
                            !decodeIsVecScoreboard
  private val decodeIsVpu = decodeIs64B | decodeIsVecScoreboard
  private val illegal = !(decodeIsScoreboard | decodeIsMat | decodeIsDma | decodeIsVec | decodeIsVpu)
  chisel3.assert(!(illegal & instQ.valid))

  sbReq.bits  := instQ.bits
  matReq.bits := instQ.bits
  dmaReq.bits := instQ.bits
  vecReq.bits := instQ.bits
  vpuReq.bits := instQ.bits
  sbReq.valid  := instQ.valid & decodeIsScoreboard
  matReq.valid := instQ.valid & decodeIsMat
  dmaReq.valid := instQ.valid & decodeIsDma
  vecReq.valid := instQ.valid & decodeIsVec
  vpuReq.valid := instQ.valid & decodeIsVpu

  instQ.ready := (sbReq.ready  & decodeIsScoreboard) | (matReq.ready & decodeIsMat) |
                 (dmaReq.ready & decodeIsDma)        | (vecReq.ready & decodeIsVec) | 
                 (vpuReq.ready & decodeIsVpu) | illegal

  // RR-Arbiter of write-back
  private val arb = Module(new RRArbiter(instResp.bits.cloneType, 5)).io
  arb.in(0) <> sbResp
  arb.in(1) <> matResp
  arb.in(2) <> dmaResp
  arb.in(3) <> vecResp
  arb.in(4) <> vpuResp
  instResp <> arb.out

}
