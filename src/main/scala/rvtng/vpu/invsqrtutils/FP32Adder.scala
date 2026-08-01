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

package invsqrtutils
import chisel3._
import chisel3.util._
import consts._

class FP32Adder extends Module {
    val io = IO(new Bundle {
        val subOp          = Input(Bool())
        val a           = Input(UInt(32.W))
        val b           = Input(UInt(32.W))
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(Bool()) //微小值舍入前后检测
        val out          = Output(UInt(32.W))
        val exceptionFlags = Output(UInt(5.W))
    })

    val Rec_a = recFNFromFN(8, 24, io.a)
    val Rec_b = recFNFromFN(8, 24, io.b)

    val addModule = Module(new AddRecFN(8, 24))
    addModule.io.subOp := io.subOp
    addModule.io.a := Rec_a
    addModule.io.b := Rec_b
    addModule.io.roundingMode := io.roundingMode
    addModule.io.detectTininess := io.detectTininess
    // addModule.io.roundingMode := consts.round_near_even
    // addModule.io.detectTininess := consts.tininess_afterRounding

    io.out := fNFromRecFN(8, 24, addModule.io.out)
    io.exceptionFlags := addModule.io.exceptionFlags
}