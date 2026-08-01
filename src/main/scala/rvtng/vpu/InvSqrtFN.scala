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

import invsqrtutils._

import chisel3._
import chisel3.util._
import consts._


//----------------------------------------------------------------------------
//----------------------------------------------------------------------------
class InvSqrtRawFN(expWidth: Int, sigWidth: Int) extends Module //8,24
{
    val io = IO(new Bundle {
        val in_invsqrt     = Input(UInt((expWidth + sigWidth).W))
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        // val invalidExc     = Output(Bool())
        val exceptionFlags = Output(UInt(5.W))
        val out_invsqrt    = Output(UInt((expWidth + sigWidth).W))
    })
    //将输入rawin_invsqrt转换为FN格式
    // val rawin_invsqrt_RawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    // rawin_invsqrt_RawFNToRecFN.io.invalidExc     := isSigNaNRawFloat(io.rawin_invsqrt)
    // rawin_invsqrt_RawFNToRecFN.io.infiniteExc    := false.B
    // rawin_invsqrt_RawFNToRecFN.io.in.isInf       := io.rawin_invsqrt.isInf 
    // rawin_invsqrt_RawFNToRecFN.io.in.isNaN       := io.rawin_invsqrt.isNaN 
    // rawin_invsqrt_RawFNToRecFN.io.in.isZero      := io.rawin_invsqrt.isZero 
    // rawin_invsqrt_RawFNToRecFN.io.in.sign        := io.rawin_invsqrt.sign 
    // rawin_invsqrt_RawFNToRecFN.io.in.sExp        := io.rawin_invsqrt.sExp 
    // rawin_invsqrt_RawFNToRecFN.io.in.sig         := io.rawin_invsqrt.sig ## 0.U(2.W)
    // rawin_invsqrt_RawFNToRecFN.io.roundingMode   := io.roundingMode
    // rawin_invsqrt_RawFNToRecFN.io.detectTininess := io.detectTininess
    // val recin_invsqrt                             = rawin_invsqrt_RawFNToRecFN.io.out
    // val rawin_invsqrt_RawFNToRecFN_exceptionFlags = rawin_invsqrt_RawFNToRecFN.io.exceptionFlags
    // val fnin_invsqrt                              = fNFromRecFN(expWidth, sigWidth, recin_invsqrt)

    val fnin_invsqrt = io.in_invsqrt

    /*------------------------------------------------------------------------
    Householder’s Method，简称HHM
    *------------------------------------------------------------------------*/
    // val magic_num = "h5f29621b".U
    val magic_num = "h5f375a86".U
    val int_sub   = magic_num - (fnin_invsqrt >> 1.U); //计算倒数开方粗略值

    //下面两个乘法器调用主要是计算fnin_invsqrt * int_sub * int_sub
    // val mulFN_HHM_1 = Module(new MulFN(expWidth, sigWidth))
    // mulFN_HHM_1.io.a              := int_sub
    // mulFN_HHM_1.io.b              := int_sub
    // mulFN_HHM_1.io.detectTininess := io.detectTininess
    // mulFN_HHM_1.io.roundingMode   := io.roundingMode
    // val mulFN_HHM_1_out            = mulFN_HHM_1.io.out
    // val mulFN_HHM_1_exceptionFlags = mulFN_HHM_1.io.exceptionFlags

    // val mulFN_HHM_2 = Module(new MulFN(expWidth, sigWidth))
    // mulFN_HHM_2.io.a              := mulFN_HHM_1_out
    // mulFN_HHM_2.io.b              := fnin_invsqrt
    // mulFN_HHM_2.io.detectTininess := io.detectTininess
    // mulFN_HHM_2.io.roundingMode   := io.roundingMode
    // val mulFN_HHM_2_out            = mulFN_HHM_2.io.out
    // val mulFN_HHM_2_exceptionFlags = mulFN_HHM_2.io.exceptionFlags

    // //下面主要是对结果进行非牛顿迭代法修正(用简写adj)，主要参照论文提出的方法，详见doc
    // val const_num1 = "h400075BE".U
    // // val const_num2 = "h3FC424BE".U
    // // val const_num3 = "h3F06A4AB".U
    // val const_num4 = "h3f80d268".U

    // val mulFN_adj_1 = Module(new MulFN(expWidth, sigWidth))
    // mulFN_adj_1.io.a              := const_num4
    // mulFN_adj_1.io.b              := mulFN_HHM_2_out
    // mulFN_adj_1.io.detectTininess := io.detectTininess
    // mulFN_adj_1.io.roundingMode   := io.roundingMode
    // val mulFN_adj_1_out            = mulFN_adj_1.io.out
    // val mulFN_adj_1_exceptionFlags = mulFN_adj_1.io.exceptionFlags

    // val addFN_adj_2 = Module(new AddFN(expWidth, sigWidth))
    // addFN_adj_2.io.subOp          := true.B;
    // addFN_adj_2.io.a              := const_num1
    // addFN_adj_2.io.b              := mulFN_adj_1_out
    // addFN_adj_2.io.detectTininess := io.detectTininess
    // addFN_adj_2.io.roundingMode   := io.roundingMode
    // val addFN_adj_2_out            = addFN_adj_2.io.out
    // val addFN_adj_2_exceptionFlags = addFN_adj_2.io.exceptionFlags

    // val mulFN_adj_3 = Module(new MulFN(expWidth, sigWidth))
    // mulFN_adj_3.io.a              := int_sub
    // mulFN_adj_3.io.b              := addFN_adj_2_out
    // mulFN_adj_3.io.detectTininess := io.detectTininess
    // mulFN_adj_3.io.roundingMode   := io.roundingMode
    // val mulFN_adj_3_out            = mulFN_adj_3.io.out
    // val mulFN_adj_3_exceptionFlags = mulFN_adj_3.io.exceptionFlags

    /*------------------------------------------------------------------------
    牛顿迭代法核心修正
    *------------------------------------------------------------------------*/
    val iter1                = Module(new newton_iter_invsqrt(expWidth, sigWidth))
    iter1.io.data_in        := fnin_invsqrt
    iter1.io.iter_in        := int_sub
    // iter1.io.iter_in        := mulFN_adj_3_out
    iter1.io.detectTininess := io.detectTininess
    iter1.io.roundingMode   := io.roundingMode
    val iter1_out            = iter1.io.iter_out
    val iter1_exceptionFlags = iter1.io.exceptionFlags

    val reg0_fnin_invsqrt = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(fnin_invsqrt)))))))))))

    val iter2                = Module(new newton_iter_invsqrt(expWidth, sigWidth))
    iter2.io.data_in        := reg0_fnin_invsqrt
    iter2.io.iter_in        := iter1_out
    iter2.io.detectTininess := io.detectTininess
    iter2.io.roundingMode   := io.roundingMode
    val iter2_out            = iter2.io.iter_out
    val iter2_exceptionFlags = iter2.io.exceptionFlags

    val reg1_fnin_invsqrt = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg0_fnin_invsqrt)))))))))))
    val reg1_iter2_exceptionFlags = iter2_exceptionFlags | RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(iter1_exceptionFlags)))))))))))

    val iter3                = Module(new newton_iter_invsqrt(expWidth, sigWidth))
    iter3.io.data_in        := reg1_fnin_invsqrt
    iter3.io.iter_in        := iter2_out
    iter3.io.detectTininess := io.detectTininess
    iter3.io.roundingMode   := io.roundingMode
    val iter3_out            = iter3.io.iter_out
    val iter3_exceptionFlags = iter3.io.exceptionFlags

    // io.rawout_invsqrt := rawFloatFromFN(expWidth, sigWidth, iter3_out)
    // io.out_invsqrt := iter3_out
    // io.exceptionFlags := iter1.io.exceptionFlags       |
    //                      iter2.io.exceptionFlags       |
    //                      iter3.io.exceptionFlags

    io.out_invsqrt := iter3_out
    io.exceptionFlags := iter3_exceptionFlags | RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg1_iter2_exceptionFlags)))))))))))

    // io.exceptionFlags := mulFN_HHM_1.io.exceptionFlags |
    //                      mulFN_HHM_2.io.exceptionFlags |
    //                      mulFN_adj_1.io.exceptionFlags |
    //                      addFN_adj_2.io.exceptionFlags |
    //                      mulFN_adj_3.io.exceptionFlags |
    //                      iter1.io.exceptionFlags       |
    //                      iter2.io.exceptionFlags       |
    //                      iter3.io.exceptionFlags

    // io.invalidExc := isSigNaNRawFloat(io.rawin_invsqrt)
}

//----------------------------------------------------------------------------
//牛顿迭代法模块设计，内部插了11级流水
//----------------------------------------------------------------------------
class newton_iter_invsqrt(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val data_in = Input(UInt((expWidth + sigWidth).W))
        val iter_in = Input(UInt((expWidth + sigWidth).W))
        val roundingMode = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        val iter_out = Output(UInt((expWidth + sigWidth).W))
        val exceptionFlags = Output(UInt(5.W))
    })

    val threehalf = "h3FC00000".U
    val onehalf   = "h3F000000".U

    //计算data_in * iter_in
    val mulFN_newton_1 = Module(new FP32Multiplier())
    mulFN_newton_1.io.a              := io.iter_in
    mulFN_newton_1.io.b              := io.data_in
    mulFN_newton_1.io.detectTininess := io.detectTininess
    mulFN_newton_1.io.roundingMode   := io.roundingMode
    val mulFN_newton_1_out            = mulFN_newton_1.io.out
    val mulFN_newton_1_exceptionFlags = mulFN_newton_1.io.exceptionFlags

    //加一级流水
    val reg0_iter_in = RegNext(RegNext(io.iter_in))

    //计算data_in * iter_in * 0.5
    val mulFN_newton_2 = Module(new FP32Multiplier())
    mulFN_newton_2.io.a              := mulFN_newton_1_out
    mulFN_newton_2.io.b              := onehalf
    mulFN_newton_2.io.detectTininess := io.detectTininess
    mulFN_newton_2.io.roundingMode   := io.roundingMode
    val mulFN_newton_2_out            = mulFN_newton_2.io.out
    val mulFN_newton_2_exceptionFlags = mulFN_newton_2.io.exceptionFlags

    //加一级流水
    val reg1_iter_in                       = RegNext(RegNext(reg0_iter_in))
    val reg1_mulFN_newton_2_exceptionFlags = mulFN_newton_2_exceptionFlags | RegNext(RegNext(mulFN_newton_1_exceptionFlags))

    //计算0.5 * data_in * iter_in * iter_in
    val mulFN_newton_3 = Module(new FP32Multiplier())
    mulFN_newton_3.io.a              := mulFN_newton_2_out
    mulFN_newton_3.io.b              := reg1_iter_in
    mulFN_newton_3.io.detectTininess := io.detectTininess
    mulFN_newton_3.io.roundingMode   := io.roundingMode
    val mulFN_newton_3_out            = mulFN_newton_3.io.out
    val mulFN_newton_3_exceptionFlags = mulFN_newton_3.io.exceptionFlags

    //加一级流水
    val reg2_iter_in                       = RegNext(RegNext(reg1_iter_in))
    val reg2_mulFN_newton_3_exceptionFlags = mulFN_newton_3_exceptionFlags | RegNext(RegNext(reg1_mulFN_newton_2_exceptionFlags))

    //计算1.5 - 0.5 * data_in * iter_in * iter_in
    val addFN_newton_4 = Module(new FP32Adder())
    addFN_newton_4.io.subOp          := true.B;
    addFN_newton_4.io.a              := threehalf
    addFN_newton_4.io.b              := mulFN_newton_3_out
    addFN_newton_4.io.detectTininess := io.detectTininess
    addFN_newton_4.io.roundingMode   := io.roundingMode
    val addFN_newton_4_out            = addFN_newton_4.io.out
    val addFN_newton_4_exceptionFlags = addFN_newton_4.io.exceptionFlags

    //加一级流水
    val reg3_iter_in                       = RegNext(RegNext(RegNext(reg2_iter_in)))
    val reg3_addFN_newton_4_exceptionFlags = addFN_newton_4_exceptionFlags | RegNext(RegNext(RegNext(reg2_mulFN_newton_3_exceptionFlags)))


    //计算iter_in * (1.5 - 0.5 * data_in * iter_in * iter_in)
    val mulFN_newton_5 = Module(new FP32Multiplier())
    mulFN_newton_5.io.a              := reg3_iter_in
    mulFN_newton_5.io.b              := addFN_newton_4_out
    mulFN_newton_5.io.detectTininess := io.detectTininess
    mulFN_newton_5.io.roundingMode   := io.roundingMode
    val mulFN_newton_5_out            = mulFN_newton_5.io.out
    val mulFN_newton_5_exceptionFlags = mulFN_newton_5.io.exceptionFlags

    io.iter_out       := mulFN_newton_5_out
    io.exceptionFlags := mulFN_newton_5_exceptionFlags | RegNext(RegNext(mulFN_newton_5_exceptionFlags))
}


//----------------------------------------------------------------------------
//5个输入，分别是输入数in_invsqrt，输出数out_invsqrt，四舍五入模式，微小值舍入前后检测以及异常检测标志位
//----------------------------------------------------------------------------
class InvSqrtFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val in_invsqrt     = Input(UInt((expWidth + sigWidth).W)) //9+24=32
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(UInt(1.W)) //微小值舍入前后检测
        val out_invsqrt    = Output(UInt((expWidth + sigWidth).W)) //32
        val exceptionFlags = Output(UInt(5.W))
    })
    //------------------------------------------------------------------------
    //------------------------------------------------------------------------
    val invsqrtRawFN = Module(new InvSqrtRawFN(expWidth, sigWidth))
    invsqrtRawFN.io.in_invsqrt := io.in_invsqrt
    // invsqrtRawFN.io.rawin_invsqrt  := rawFloatFromFN(expWidth, sigWidth, io.in_invsqrt)
    invsqrtRawFN.io.roundingMode   := io.roundingMode
    invsqrtRawFN.io.detectTininess := io.detectTininess
    //------------------------------------------------------------------------
    //------------------------------------------------------------------------
    // val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    // roundRawFNToRecFN.io.invalidExc     := invsqrtRawFN.io.invalidExc
    // roundRawFNToRecFN.io.infiniteExc    := false.B
    // roundRawFNToRecFN.io.in.isInf       := invsqrtRawFN.io.rawout_invsqrt.isInf 
    // roundRawFNToRecFN.io.in.isNaN       := invsqrtRawFN.io.rawout_invsqrt.isNaN 
    // roundRawFNToRecFN.io.in.isZero      := invsqrtRawFN.io.rawout_invsqrt.isZero 
    // roundRawFNToRecFN.io.in.sign        := invsqrtRawFN.io.rawout_invsqrt.sign 
    // roundRawFNToRecFN.io.in.sExp        := invsqrtRawFN.io.rawout_invsqrt.sExp 
    // roundRawFNToRecFN.io.in.sig         := invsqrtRawFN.io.rawout_invsqrt.sig ## 0.U(2.W)
    // roundRawFNToRecFN.io.roundingMode   := io.roundingMode
    // roundRawFNToRecFN.io.detectTininess := io.detectTininess

    val reg_in_invsqrt = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(io.in_invsqrt)))))))))))))))))))))))))))))))))
    when(reg_in_invsqrt(expWidth + sigWidth - 1) === 1.U) //负数
    {
        io.out_invsqrt := "h7FC00000".U
    }.elsewhen(reg_in_invsqrt === 0.U)   //等于+0时
    {
        io.out_invsqrt := "h7F800000".U
    }.elsewhen(reg_in_invsqrt === "h7F800000".U)  //等于+inf时
    {
        io.out_invsqrt := "h00000000".U
    }.elsewhen(reg_in_invsqrt === "h7FC00000".U) //等于NaN时
    {
        io.out_invsqrt := "h7FC00000".U
    }.otherwise
    {
        // io.out_invsqrt := fNFromRecFN(expWidth, sigWidth, roundRawFNToRecFN.io.out)
        io.out_invsqrt := invsqrtRawFN.io.out_invsqrt
    }
    // io.exceptionFlags                   := roundRawFNToRecFN.io.exceptionFlags | invsqrtRawFN.io.exceptionFlags
    io.exceptionFlags                   := invsqrtRawFN.io.exceptionFlags
}