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

package rvtng.fpmath

import chisel3._
import chisel3.util._
import rvtng.mat.DPArrayTop
import _root_.circt.stage.ChiselStage

class FPUnpackModule(wE: Int, wF: Int, bias: Int) extends Module {
  val fp = IO(Input(UInt(32.W)))
  val exp = IO(Output(SInt(wE.W)))
  val frac = IO(Output(UInt(wF.W)))
  require(wE == 9)

  val iSgn = fp(31)
  val iExp = fp(30, 23)
  val iFrac = fp(22, 0) // .23b

  exp := ((iExp +& 1.U) - 127.U).asSInt
  val extFrac = Cat(0.U(1.W), Mux(iExp === 0.U, 0.U(1.W), 1.U(1.W)), iFrac) // s1.23b == 25b
  val sFrac = Mux(iSgn.asBool, -extFrac, extFrac).asSInt

  if(wF <= 25) {
    frac := sFrac(24, 25 - wF)
  } else {
    frac := Cat(sFrac, 0.S).asSInt
  }
}

class TestFPUnpackPack extends Module {
  val fpIn = IO(Input(UInt(32.W)))
  val fpOut = IO(Output(UInt(32.W)))
  val unpack = Module(new FPUnpackModule(9, 25, 0))
  val pack = Module(new SignedFPToFPModule(9, 25, 0))
  unpack.fp := fpIn
  pack.exp := unpack.exp
  pack.frac := unpack.frac
  fpOut := pack.fp
}

object TestFPUnpackPack extends App {
  ChiselStage.emitSystemVerilog(
    new TestFPUnpackPack(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/TestFPUnpackPack"
    )
  )
}



