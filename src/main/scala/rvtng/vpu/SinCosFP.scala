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

import sincosutils._


import chisel3._
import chisel3.util._
import consts._


// /*------------------------------------------------------------------------
// 异常处理模块，当输入为NaN或Inf时，输出异常标志，最终结果应为NaN
// 输入：32位浮点，此处expWidth为8，sigWidth为24
// 输出：布尔，1表示异常，0表示正常
// *------------------------------------------------------------------------*/
// class exception_handling(expWidth: Int, sigWidth: Int) extends Module
// {
//     val io = IO(new Bundle {
//         val in = Input(UInt((expWidth + sigWidth).W))
//         val exceptionFlags = Output(Bool())
//     })

//     io.exceptionFlags := io.in(expWidth + sigWidth - 2, sigWidth - 1) === "hff".U 
// }


/*------------------------------------------------------------------------
角度预处理模块
输入：32位浮点，此处expWidth为8，sigWidth为24

输出：符号位+整数位+小数位，对于cordic算法，整数位仅需要2位，小数位则可以根据需求精度自定义
*------------------------------------------------------------------------*/
class angle_preprocess(expWidth: Int, sigWidth: Int, IN_intWidth: Int, IN_fraWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val in = Input(UInt((expWidth + sigWidth).W))
        val roundingMode = Input(UInt(3.W))
        val overflow = Output(Bool())
        val out = Output(UInt((IN_intWidth + IN_fraWidth + 1).W))
        val Q = Output(UInt(2.W))  //Q=0,1,2,3，分别表示第一象限，第二象限，第三象限，第四象限
        val sign = Output(Bool()) //符号位
        val is_inf_nan = Output(Bool()) //ture代表输入数是inf和nan
    })
    val shift_buffer = Wire(UInt((IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)).W))  //2+23+126=151
    val Q_buffer = Wire(UInt(2.W))
    val in_exp = io.in(expWidth + sigWidth - 2, sigWidth - 1)  //8
    val in_sig = io.in(sigWidth - 2, 0) // 23
    val in_sign = io.in(expWidth + sigWidth - 1) //1
    val in_sig_extend = in_sig ## 0.U(((1 << (expWidth - 1)) - 1 - 1).W) //23+126
    val PI     = "b01100100100001111110110110000000".U  //这里应该能利用生成器生成，根据IN_intWidth和IN_fraWidth
    val PI_2   = "b00110010010000111111011011000000".U
    val PI_rcp = "b00001010001011111001100000110000".U
    val in_sig_mul_PI_rcp = Wire(UInt((sigWidth + IN_intWidth + IN_fraWidth + 1).W))  //24+2+29+1=56，但其实sig最大是2,2/pi小于1

    val tag = Wire(Bool())  //标志是否是大于pi/2的数
    val in_sig_mul_PI_rcp_mul_PI_2 = Wire(UInt((sigWidth + IN_intWidth + IN_fraWidth + 1 - 4 + IN_intWidth + IN_fraWidth + 1).W)) //56-4+32 = 84

    in_sig_mul_PI_rcp := (1.U ## in_sig) * PI_rcp 

    //val reg_in_sig_mul_PI_rcp = RegInit(0.U((sigWidth + IN_intWidth + IN_fraWidth + 1).W))

    when(in_exp > 127.U || in_exp === 127.U && (1.U ## in_sig ## 0.U((IN_fraWidth - sigWidth + 1).W)) > PI_2)  //这里的127应该也能替换，1<<expWidth - 1
    {
        shift_buffer := (in_sig_mul_PI_rcp(sigWidth + IN_intWidth + IN_fraWidth - 2, 0) ## 0.U(((IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2))-(sigWidth + IN_intWidth + IN_fraWidth - 1)).W)) << (in_exp - ((1 << (expWidth - 1)).U - 1.U) + 1.U)
        Q_buffer := Mux(in_sign, 3.U - shift_buffer((IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)) - 1, (IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)) - 2), shift_buffer((IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)) - 1, (IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)) - 2))
        in_sig_mul_PI_rcp_mul_PI_2 := shift_buffer(IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2) - 3, IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2) - 3 - (sigWidth + IN_intWidth + IN_fraWidth - 4)) * PI_2 //只取小数部分乘以 PI/2
        tag := true.B
    }.elsewhen(in_exp === 127.U && (1.U ## in_sig ## 0.U((IN_fraWidth - sigWidth + 1).W)) <= PI_2)
    {
        shift_buffer := (1.U(2.W) ## in_sig_extend)
        in_sig_mul_PI_rcp_mul_PI_2 := 0.U
        Q_buffer := Mux(in_sign, 3.U, 0.U)
        tag := false.B
    }.elsewhen(in_exp < 127.U && in_exp > 0.U)
    {
        shift_buffer := (1.U(2.W) ## in_sig_extend) >> ((1 << (expWidth - 1)).U - 1.U - in_exp)
        in_sig_mul_PI_rcp_mul_PI_2 := 0.U
        Q_buffer := Mux(in_sign, 3.U, 0.U)
        tag := false.B
    }.otherwise
    {
        shift_buffer := (0.U(2.W) ## in_sig_extend) >> ((1 << (expWidth - 1)).U - 1.U - 1.U)
        in_sig_mul_PI_rcp_mul_PI_2 := 0.U
        Q_buffer := Mux(in_sign, 3.U, 0.U)
        tag := false.B
    }
    
    // val reg_shift_buffer = RegInit(0.U((IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)).W))
    // reg_shift_buffer := shift_buffer

    val rounder1 = Module(new round((IN_intWidth + sigWidth - 1 + ((1 << (expWidth - 1)) - 2)), IN_intWidth + IN_fraWidth))
    rounder1.io.in := shift_buffer
    // rounder1.io.in := reg_shift_buffer
    rounder1.io.roundingMode := io.roundingMode
    val overflow1 = rounder1.io.overflow
    val round_out1 = rounder1.io.out

    // val reg_in_sig_mul_PI_rcp_mul_PI_2 = RegInit(0.U((sigWidth + IN_intWidth + IN_fraWidth + 1 - 4 + IN_intWidth + IN_fraWidth + 1).W))
    // reg_in_sig_mul_PI_rcp_mul_PI_2 := in_sig_mul_PI_rcp_mul_PI_2

    val rounder2 = Module(new round((sigWidth + IN_intWidth + IN_fraWidth + 1 - 4 + IN_intWidth + IN_fraWidth + 1 - 1), IN_intWidth + IN_fraWidth))
    rounder2.io.in := in_sig_mul_PI_rcp_mul_PI_2(sigWidth + IN_intWidth + IN_fraWidth + 1 - 4 + IN_intWidth + IN_fraWidth + 1 - 2, 0) //去除符号位
    // rounder2.io.in := reg_in_sig_mul_PI_rcp_mul_PI_2(sigWidth + IN_intWidth + IN_fraWidth + 1 - 4 + IN_intWidth + IN_fraWidth + 1 - 2, 0) //去除符号位
    rounder2.io.roundingMode := io.roundingMode
    val overflow2 = rounder2.io.overflow || overflow1
    val round_out2 = rounder2.io.out

    // val reg_tag = RegInit(false.B)
    // reg_tag := tag

    // val reg_Q_buffer = RegInit(false.B)
    // reg_Q_buffer := Q_buffer

    // val reg_in_sign = RegInit(false.B)
    // reg_in_sign := in_sign

    io.overflow := Mux(tag, overflow2, overflow1)
    io.out := 0.U(1.W) ## Mux(tag, round_out2, round_out1)  //加上符号位，1_2_29
    io.Q := Q_buffer
    io.sign := in_sign
    io.is_inf_nan := in_exp === "hff".U
    // io.overflow := Mux(reg_tag, overflow2, overflow1)
    // io.out := 0.U(1.W) ## Mux(reg_tag, round_out2, round_out1)  //加上符号位，1_2_29
    // io.Q := reg_Q_buffer
    // io.sign := reg_in_sign
}

/*------------------------------------------------------------------------
cordic核心计算模块
输入：32位定点，格式为1_2_29，1位符号位，2位整数位，29位小数位，此处IN_intWidth = 2，IN_fraWidth = 29

输出：sin和cos的定点形式
*------------------------------------------------------------------------*/
class cordic_core(IN_intWidth: Int, IN_fraWidth: Int, iter_num: Int) extends Module
{
    val io = IO(new Bundle {
        val in = Input(UInt((IN_intWidth + IN_fraWidth + 1).W)) //1_2_29
        val Q  = Input(UInt(2.W)) //Q=0,1,2,3，分别表示第一象限，第二象限，第三象限，第四象限
        val sign = Input(Bool()) //符号位
        val is_inf_nan = Input(Bool()) //ture代表输入数是inf和nan
        val roundingMode = Input(UInt(3.W))
        val angle_preprocess_overflow = Input(Bool())
        val overflow = Output(Bool())
        val out_cos = Output(UInt((IN_intWidth + IN_fraWidth + 1).W))
        val out_sin = Output(UInt((IN_intWidth + IN_fraWidth + 1).W))
        val out_is_inf_nan = Output(Bool()) //ture代表输入数是inf和nan
    })
    
    val actan_table = new Array[SInt](iter_num)
    actan_table(0)  = 421657428.S(32.W) //这里或许可以用scala生成
    actan_table(1)  = 248918914.S(32.W)
    actan_table(2)  = 131521918.S(32.W)
    actan_table(3)  =  66762579.S(32.W)
    actan_table(4)  =  33510843.S(32.W)
    actan_table(5)  =  16771757.S(32.W)
    actan_table(6)  =   8387925.S(32.W)
    actan_table(7)  =   4194218.S(32.W)
    actan_table(8)  =   2097141.S(32.W)
    actan_table(9)  =   1048574.S(32.W)
    actan_table(10) =    524287.S(32.W)
    actan_table(11) =    262143.S(32.W)
    actan_table(12) =    131071.S(32.W)
    actan_table(13) =     65535.S(32.W)
    actan_table(14) =     32767.S(32.W)
    actan_table(15) =     16383.S(32.W)
    actan_table(16) =      8191.S(32.W)
    actan_table(17) =      4095.S(32.W)
    actan_table(18) =      2047.S(32.W)
    actan_table(19) =      1023.S(32.W)
    actan_table(20) =       511.S(32.W)
    actan_table(21) =       255.S(32.W)
    actan_table(22) =       127.S(32.W)
    actan_table(23) =        63.S(32.W)
    actan_table(24) =        31.S(32.W)
    actan_table(25) =        15.S(32.W)
    actan_table(26) =         7.S(32.W)
    actan_table(27) =         4.S(32.W)
    actan_table(28) =         2.S(32.W)
    actan_table(29) =         1.S(32.W)

    val K = 326016418.S(32.W) //0.6072529350088812561694  1/K = 1.6467602581210658555

    val angle_initial = io.in.asSInt //输入角度，1_2_29格式
    
    val x_reg_vec = RegInit(VecInit(Seq.fill(iter_num)(0.S((IN_intWidth + IN_fraWidth + 1).W)))) // cos
    val y_reg_vec = RegInit(VecInit(Seq.fill(iter_num)(0.S((IN_intWidth + IN_fraWidth + 1).W)))) // sin
    val angle_reg_vec = RegInit(VecInit(Seq.fill(iter_num)(0.S((IN_intWidth + IN_fraWidth + 1).W)))) // 目标角度
    // val angle_preprocess_overflow_and_inf_and_Q_and_sign_reg_vec = RegInit(VecInit(Seq.fill(iter_num)(0.U(5.W)))) // Q和符号位
    val data_information = RegInit(VecInit(Seq.fill(iter_num)(0.U(5.W)))) // angle_preprocess的overflow ## is_inf_nan ## Q ## sign

    // cordic核心计算逻辑
    x_reg_vec(0) := (1<<IN_fraWidth).S((IN_intWidth + IN_fraWidth + 1).W)
    y_reg_vec(0) := 0.S((IN_intWidth + IN_fraWidth + 1).W)
    angle_reg_vec(0) := io.in.asSInt
    // inf_and_Q_and_sign_reg_vec(0) := Cat(io.is_inf_nan, io.sign, io.Q) //将Q和符号位合并为一个3位的向量
    data_information(0) := Cat(io.angle_preprocess_overflow, io.is_inf_nan, io.sign, io.Q) //将Q和符号位合并为一个3位的向量
    for (i <-1 to (iter_num - 1)) 
    // for (i <-1 to (iter_num)) 
    {
        // when(angle_reg_vec(i - 1) >= 0.S)
        when(angle_reg_vec(i - 1)(IN_intWidth + IN_fraWidth) === 0.U(1.W))
        {
            x_reg_vec(i) := x_reg_vec(i - 1) - (y_reg_vec(i - 1) >> (i - 1))
            y_reg_vec(i) := y_reg_vec(i - 1) + (x_reg_vec(i - 1) >> (i - 1))
            angle_reg_vec(i) := angle_reg_vec(i - 1) - actan_table(i - 1) 
        }.otherwise
        {
            x_reg_vec(i) := x_reg_vec(i - 1) + (y_reg_vec(i - 1) >> (i - 1))
            y_reg_vec(i) := y_reg_vec(i - 1) - (x_reg_vec(i - 1) >> (i - 1))
            angle_reg_vec(i) := angle_reg_vec(i - 1) + actan_table(i - 1)   
        }
        // inf_and_Q_and_sign_reg_vec(i) := inf_and_Q_and_sign_reg_vec(i - 1)
        data_information(i) := data_information(i - 1)
    }

    val mul_buffer_x = Wire(SInt((IN_intWidth + IN_fraWidth + 1 + IN_intWidth + IN_fraWidth + 1 - 1).W))
    val mul_buffer_y = Wire(SInt((IN_intWidth + IN_fraWidth + 1 + IN_intWidth + IN_fraWidth + 1 - 1).W))
    mul_buffer_x := x_reg_vec(iter_num - 1) * K
    mul_buffer_y := y_reg_vec(iter_num - 1) * K

    //最后x结果舍入模块
    val rounder_x = Module(new round((IN_intWidth + IN_fraWidth + 1 + IN_intWidth + IN_fraWidth + 1 - 3), IN_intWidth + IN_fraWidth + 1))
    rounder_x.io.in := mul_buffer_x(IN_intWidth + IN_fraWidth + 1 + IN_intWidth + IN_fraWidth + 1 - 4,0)
    rounder_x.io.roundingMode := io.roundingMode

    // val reg_x          = RegInit(0.U((IN_intWidth + IN_fraWidth + 1).W))
    // val reg_x_overflow = RegInit(false.B)
    // reg_x             := rounder_x.io.out
    // reg_x_overflow    := rounder_x.io.overflow

    //最后y结果舍入模块
    val rounder_y = Module(new round((IN_intWidth + IN_fraWidth + 1 + IN_intWidth + IN_fraWidth + 1 - 3), IN_intWidth + IN_fraWidth + 1))
    rounder_y.io.in := mul_buffer_y(IN_intWidth + IN_fraWidth + 1 + IN_intWidth + IN_fraWidth + 1 - 3,0)
    rounder_y.io.roundingMode := io.roundingMode

    // val reg_y          = RegInit(0.U((IN_intWidth + IN_fraWidth + 1).W))
    // val reg_y_overflow = RegInit(false.B)
    // reg_y             := rounder_y.io.out
    // reg_y_overflow    := rounder_y.io.overflow

    val rounder_overflow = rounder_x.io.overflow || rounder_y.io.overflow
    // io.overflow := reg_x_overflow || reg_y_overflow

    // val reg_Q_and_sign_reg_vec = RegInit(0.U(3.W))
    // reg_Q_and_sign_reg_vec := Q_and_sign_reg_vec(iter_num - 1)

    // when(reg_Q_and_sign_reg_vec(1,0) === 0.U) 
    // {
    //     io.out_cos := Mux(reg_Q_and_sign_reg_vec(2), reg_y, reg_x)
    //     io.out_sin := Mux(reg_Q_and_sign_reg_vec(2), reg_x, reg_y)
    // }.elsewhen(reg_Q_and_sign_reg_vec(1,0) === 1.U)
    // {
    //     io.out_cos := Mux(reg_Q_and_sign_reg_vec(2), ~reg_x(IN_intWidth + IN_fraWidth) ## (~reg_x(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~reg_y(IN_intWidth + IN_fraWidth) ## (~reg_y(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
    //     io.out_sin := Mux(reg_Q_and_sign_reg_vec(2), reg_y, reg_x)
    // }.elsewhen(reg_Q_and_sign_reg_vec(1,0) === 2.U)
    // {
    //     io.out_cos := Mux(reg_Q_and_sign_reg_vec(2), ~reg_y(IN_intWidth + IN_fraWidth) ## (~reg_y(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~reg_x(IN_intWidth + IN_fraWidth) ## (~reg_x(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
    //     io.out_sin := Mux(reg_Q_and_sign_reg_vec(2), ~reg_x(IN_intWidth + IN_fraWidth) ## (~reg_x(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~reg_y(IN_intWidth + IN_fraWidth) ## (~reg_y(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
    // }.otherwise //io.Q === 3.U
    // {
    //     io.out_cos := Mux(reg_Q_and_sign_reg_vec(2), reg_x, reg_y)
    //     io.out_sin := Mux(reg_Q_and_sign_reg_vec(2), ~reg_y(IN_intWidth + IN_fraWidth) ## (~reg_y(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~reg_x(IN_intWidth + IN_fraWidth) ## (~reg_x(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
    // }

    when(data_information(iter_num - 1)(1,0) === 0.U) 
    {
        io.out_cos := Mux(data_information(iter_num - 1)(2), rounder_y.io.out, rounder_x.io.out)
        io.out_sin := Mux(data_information(iter_num - 1)(2), rounder_x.io.out, rounder_y.io.out)
    }.elsewhen(data_information(iter_num - 1)(1,0) === 1.U)
    {
        io.out_cos := Mux(data_information(iter_num - 1)(2), ~rounder_x.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_x.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~rounder_y.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_y.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
        io.out_sin := Mux(data_information(iter_num - 1)(2), rounder_y.io.out, rounder_x.io.out)
    }.elsewhen(data_information(iter_num - 1)(1,0) === 2.U)
    {
        io.out_cos := Mux(data_information(iter_num - 1)(2), ~rounder_y.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_y.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~rounder_x.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_x.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
        io.out_sin := Mux(data_information(iter_num - 1)(2), ~rounder_x.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_x.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~rounder_y.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_y.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
    }.otherwise //io.Q === 3.U
    {
        io.out_cos := Mux(data_information(iter_num - 1)(2), rounder_x.io.out, rounder_y.io.out)
        io.out_sin := Mux(data_information(iter_num - 1)(2), ~rounder_y.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_y.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U), ~rounder_x.io.out(IN_intWidth + IN_fraWidth) ## (~rounder_x.io.out(IN_intWidth + IN_fraWidth - 1,0) + 1.U))
    }
    io.out_is_inf_nan := data_information(iter_num - 1)(3)
    io.overflow := rounder_overflow || data_information(iter_num - 1)(4)
}

/*------------------------------------------------------------------------
定点结果转化为浮点数模块
输入：32位定点，格式为1_2_29，1位符号位，2位整数位，29位小数位，此处IN_intWidth = 2，IN_fraWidth = 29，负数是补码形式

输出：sin和cos的定点形式
*------------------------------------------------------------------------*/
class int2fp(expWidth: Int, sigWidth: Int, IN_intWidth: Int, IN_fraWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val in = Input(UInt((IN_intWidth + IN_fraWidth + 1).W)) //1_2_29
        val roundingMode = Input(UInt(3.W))
        val in_overflow = Input(Bool())
        val is_inf_nan = Input(Bool()) //ture代表输入数是inf和nan
        val overflow = Output(Bool())
        val out_fp = Output(UInt((expWidth + sigWidth).W)) //24+8
    })
    val in_sign_magnitude = Mux(io.in(IN_intWidth + IN_fraWidth), io.in(IN_intWidth + IN_fraWidth) ## (~io.in(IN_intWidth + IN_fraWidth - 1,0) + 1.U), io.in)
    val first_one_position = Wire(UInt((log2Ceil(IN_intWidth + IN_fraWidth + 1)).W)) //第一个1的位置
    first_one_position := 0.U
    for (i <- 0 to (IN_fraWidth)) //从小数部分开始找第一个1的位置
    {
        when(in_sign_magnitude(i) === 1.U) //找到第一个1的位置
        {
            first_one_position := i.U
        }
    }

    val fra_shift = Wire(UInt(IN_fraWidth.W)) //小数部分左移

    fra_shift := in_sign_magnitude(IN_fraWidth - 1, 0) << (IN_fraWidth.U - first_one_position)//小数部分左移
    
    val reg_fra_shift = RegInit(0.U((IN_fraWidth).W))
    reg_fra_shift := fra_shift

    val reg_E = RegInit(0.U((expWidth).W))
    reg_E := 127.U - (IN_fraWidth.U - first_one_position)

    val reg_sign = RegInit(0.U((1).W))
    reg_sign := io.in(IN_intWidth + IN_fraWidth)

    val reg_is_inf_nan = RegInit(0.U((1).W))
    reg_is_inf_nan := io.is_inf_nan

    val reg_in_overflow = RegInit(false.B)
    reg_in_overflow := io.in_overflow

    val rounder_int2fp = Module(new round(IN_fraWidth + 1, sigWidth))
    // rounder_int2fp.io.in := 0.U(1.W) ## fra_shift //加上正数符号位，加上符号位的原因只是舍入模块处理的是有符号数
    rounder_int2fp.io.in := 0.U(1.W) ## reg_fra_shift //加上正数符号位，加上符号位的原因只是舍入模块处理的是有符号数
    rounder_int2fp.io.roundingMode := io.roundingMode

    // val reg_rounder_int2fp          = RegInit(0.U((sigWidth).W))
    // val reg_rounder_int2fp_overflow = RegInit(false.B)
    // reg_rounder_int2fp             := rounder_int2fp.io.out
    // reg_rounder_int2fp_overflow    := rounder_int2fp.io.overflow

    
    // io.overflow := reg_rounder_int2fp_overflow

    val M = Wire(UInt((sigWidth - 1).W)) //尾数部分
    // val E = Wire(UInt(expWidth.W)) //指数部分
    M := rounder_int2fp.io.out(sigWidth - 2, 0) //去掉符号位
    // M := reg_rounder_int2fp(sigWidth - 2, 0)
    // E := 127.U - (IN_fraWidth.U - first_one_position)
    // val sign = io.in(IN_intWidth + IN_fraWidth)
    // io.out_fp := Mux(io.is_inf_nan === true.B, "h7FC00000".U, Mux(io.in === 0.U, 0.U, sign ## E ## M)) //如果没有1，则输出0，否则输出符号位+指数位+尾数位
    io.out_fp := Mux(reg_is_inf_nan === true.B, "h7FC00000".U, Mux(io.in === 0.U, 0.U, reg_sign ## reg_E ## M)) //如果没有1，则输出0，否则输出符号位+指数位+尾数位
    // io.overflow := rounder_int2fp.io.overflow || io.in_overflow
    io.overflow := rounder_int2fp.io.overflow || reg_in_overflow
}

/*------------------------------------------------------------------------
top模块
输入：32位浮点数，格式为1_8_23，以及舍入方式

输出：sin和cos的浮点形式，以及溢出标志
*------------------------------------------------------------------------*/

class SinCosFN(expWidth: Int, sigWidth: Int, IN_intWidth: Int, IN_fraWidth: Int, iter_num: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt((expWidth + sigWidth).W))
    val roundingMode = Input(UInt(3.W))
    val overflow = Output(Bool())
    val out_cos = Output(UInt((expWidth + sigWidth).W))
    val out_sin = Output(UInt((expWidth + sigWidth).W))
  })

    // val exception_handler = Module(new exception_handling(expWidth, sigWidth))
    // exception_handler.io.in := io.in
    // val exception_flag = exception_handler.io.exceptionFlags

    val angle_preprocessor = Module(new angle_preprocess(expWidth, sigWidth, IN_intWidth, IN_fraWidth))
    angle_preprocessor.io.in := io.in
    angle_preprocessor.io.roundingMode := io.roundingMode

    val cordic_corer = Module(new cordic_core(IN_intWidth, IN_fraWidth, iter_num))
    cordic_corer.io.in := angle_preprocessor.io.out
    cordic_corer.io.Q  := angle_preprocessor.io.Q
    cordic_corer.io.sign := angle_preprocessor.io.sign
    cordic_corer.io.is_inf_nan := angle_preprocessor.io.is_inf_nan
    cordic_corer.io.angle_preprocess_overflow := angle_preprocessor.io.overflow
    cordic_corer.io.roundingMode := io.roundingMode

    val reg_cordic_corer_out_cos = RegInit(0.U((IN_intWidth + IN_fraWidth + 1).W))
    reg_cordic_corer_out_cos := cordic_corer.io.out_cos
    val reg_cordic_corer_out_sin = RegInit(0.U((IN_intWidth + IN_fraWidth + 1).W))
    reg_cordic_corer_out_sin := cordic_corer.io.out_sin
    val reg_cordic_corer_out_is_inf_nan = RegInit(0.U((1).W))
    reg_cordic_corer_out_is_inf_nan := cordic_corer.io.out_is_inf_nan
    val reg_cordic_corer_overflow = RegInit(false.B)
    reg_cordic_corer_overflow := cordic_corer.io.overflow

    val int2fp_cos = Module(new int2fp(expWidth, sigWidth, IN_intWidth, IN_fraWidth))
    int2fp_cos.io.in := reg_cordic_corer_out_cos
    int2fp_cos.io.roundingMode := io.roundingMode
    int2fp_cos.io.is_inf_nan := reg_cordic_corer_out_is_inf_nan
    int2fp_cos.io.in_overflow := reg_cordic_corer_overflow
    val int2fp_sin = Module(new int2fp(expWidth, sigWidth, IN_intWidth, IN_fraWidth))
    int2fp_sin.io.in := reg_cordic_corer_out_sin
    int2fp_sin.io.roundingMode := io.roundingMode
    int2fp_sin.io.is_inf_nan := reg_cordic_corer_out_is_inf_nan
    int2fp_sin.io.in_overflow := reg_cordic_corer_overflow

    // val int2fp_cos = Module(new int2fp(expWidth, sigWidth, IN_intWidth, IN_fraWidth))
    // int2fp_cos.io.in := cordic_corer.io.out_cos
    // int2fp_cos.io.roundingMode := io.roundingMode
    // int2fp_cos.io.is_inf_nan := cordic_corer.io.out_is_inf_nan
    // int2fp_cos.io.in_overflow := cordic_corer.io.overflow
    // val int2fp_sin = Module(new int2fp(expWidth, sigWidth, IN_intWidth, IN_fraWidth))
    // int2fp_sin.io.in := cordic_corer.io.out_sin
    // int2fp_sin.io.roundingMode := io.roundingMode
    // int2fp_sin.io.is_inf_nan := cordic_corer.io.out_is_inf_nan
    // int2fp_sin.io.in_overflow := cordic_corer.io.overflow

    // io.out_cos := Mux(exception_flag, "b0_11111111_0000_0000_0000_0000_0000_001".U, int2fp_cos.io.out_fp)
    // io.out_sin := Mux(exception_flag, "b0_11111111_0000_0000_0000_0000_0000_001".U, int2fp_sin.io.out_fp)
    io.out_cos := int2fp_cos.io.out_fp
    io.out_sin := int2fp_sin.io.out_fp
    io.overflow := int2fp_cos.io.overflow || int2fp_sin.io.overflow
}