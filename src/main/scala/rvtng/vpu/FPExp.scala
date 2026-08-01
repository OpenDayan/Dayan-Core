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

package rvtng.vpu

import chisel3._
import chisel3.util._

import fpexputils._

class FPExp extends Module {
    val g        = 4
    val wE       = 8
    val wF       = 23
    val wFIn     = 23
    val io       = IO(new Bundle {
        val X = Input(UInt((wE + wFIn + 1).W))
        val R = Output(UInt((wE + wFIn + 1).W))
    })
    val IEEE2SFP = Module(new InputIEEE)
    IEEE2SFP.io.X := io.X
    val SFP_X = IEEE2SFP.io.R

    // --- 阶段 0: 输入解析和初步计算 ---
    val xexn      = SFP_X(wE + wFIn + 2, wE + wFIn + 1)
    val xsign     = SFP_X(wE + wFIn).asBool
    val xexpField = SFP_X(wE + wFIn - 1, wFIn)
    val xfrac     = SFP_X(wFIn - 1, 0)

    val e0              = 100.U((wE + 2).W)
    val shiftVal        = Cat(0.U(2.W), xexpField) - e0
    val resultWillBeOne = shiftVal(wE + 1).asBool
    val mXu             = Cat(1.U(1.W), xfrac)
    val maxShift        = 33.U((wE + 1).W)
    val overflow0       = !shiftVal(wE + 1).asBool && (shiftVal(wE, 0) > maxShift)
    val shiftValIn      = shiftVal(5, 0)

    // 其他早期计算的标志位
    val ofl1 = !xsign && overflow0 && (xexn === "b01".U)
    val ofl3 = !xsign && (xexn === "b10".U)
    val ufl2 = xsign && (xexn === "b10".U)
    val ufl3 = xsign && overflow0 && (xexn === "b01".U)

    // --- 流水线寄存器 ---
    // 创建一个4级延迟的流水线来传递早期信号
    val xexn_d4  = ShiftRegister(xexn, 4)
    val xsign_d4 = ShiftRegister(xsign, 4)
    val ofl1_d4  = ShiftRegister(ofl1, 4)
    val ofl3_d4  = ShiftRegister(ofl3, 4)
    val ufl2_d4  = ShiftRegister(ufl2, 4)
    val ufl3_d4  = ShiftRegister(ufl3, 4)

    // --- 阶段 0 -> 1: 实例化移位器 ---
    val mantissa_shifter = Module(new LeftShifter)
    mantissa_shifter.io.S := shiftValIn
    mantissa_shifter.io.X := mXu
    val fixX0 = mantissa_shifter.io.R

    val ufixX = Mux(resultWillBeOne, 0.U((34).W), fixX0(56, 23))

    // --- 阶段 1 -> 2/3: 实例化 Exp_helper ---
    val exp_helper = Module(new Exp)
    exp_helper.io.XSign   := xsign // 注意: xsign 来自阶段0，这里假设 exp_helper 内部有寄存
    exp_helper.io.ufixX_i := ufixX
    val k    = exp_helper.io.K
    val expY = exp_helper.io.expY

    val needNoNorm = expY(27).asBool
    val roundBit   = Mux(needNoNorm, expY(3), expY(2))

    // --- 阶段 2 -> 3 -> 4: 流水线对齐和舍入逻辑 ---
    val expY_d1       = RegNext(expY)
    val needNoNorm_d1 = RegNext(needNoNorm)
    val k_d2          = ShiftRegister(k, 2)

    val preRoundBiasSig =
        Mux(needNoNorm_d1, Cat(127.U((wE + 2).W), expY_d1(26, 4)), Cat(126.U((wE + 2).W), expY_d1(25, 3)))

    // roundNormAddend 的操作数来自不同周期，需要对齐到第4拍
    val roundNormAddend = Cat(k_d2(8), k_d2, 0.U((wF - 1).W), roundBit)

    // --- 阶段 4: 实例化加法器并计算最终结果 ---
    val adder = Module(new IntAdder_33_Freq150_uid354)
    adder.io.Cin := false.B
    adder.io.X   := preRoundBiasSig
    adder.io.Y   := roundNormAddend
    val roundedExpSigRes = adder.io.R

    val roundedExpSig = Mux(xexn_d4 === "b01".U, roundedExpSigRes, Cat(0.U(3.W), Fill(wE - 1, 1.B), Fill(wF, 0.B)))

    // --- 阶段 4: 最终的溢出/下溢判断 ---
    val ofl2 = !xsign_d4 && roundedExpSig(wE + wF) && !roundedExpSig(wE + wF + 1) && (xexn_d4 === "b01".U)
    val ofl  = ofl1_d4 || ofl2 || ofl3_d4

    val ufl1 = roundedExpSig(wE + wF) && roundedExpSig(wE + wF + 1) && (xexn_d4 === "b01".U)
    val ufl  = ufl1 || ufl2_d4 || ufl3_d4

    val rexn = MuxCase(
      "b01".U,
      Seq(
        (xexn_d4 === "b11".U) -> "b11".U,
        ofl                   -> "b10".U,
        ufl                   -> "b00".U
      )
    )

    // --- 输出赋值 ---
    // R <= Rexn & '0' & roundedExpSig(30 downto 0);
    val SFP2IEEE = Module(new OutputIEEE)
    SFP2IEEE.io.X := Cat(rexn, 0.U(1.W), roundedExpSig(wE + wF - 1, 0))
    io.R          := SFP2IEEE.io.R
}
