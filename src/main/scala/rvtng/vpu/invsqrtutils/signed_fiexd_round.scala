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



/*------------------------------------------------------------------------
舍入模块，有符号定点数转换为有符号定点数的舍入模块，虽然输入是无符号形式，但处理过程是按照有符号处理的
     舍入方式一共有6种
     round_near_even   = "b000".U(3.W)
     round_minMag      = "b001".U(3.W)
     round_min         = "b010".U(3.W)
     round_max         = "b011".U(3.W)
     round_near_maxMag = "b100".U(3.W)
     round_odd         = "b110".U(3.W)
     这里只考虑overflow的情况，underflowd舍入方式舍入掉了，没必要设置标志位表示underflow，且采用饱和处理
输入：大位宽定点数
输出：小位宽定点数
*------------------------------------------------------------------------*/
class round(inWidth: Int, outWidth: Int) extends RawModule
{
    val io = IO(new Bundle {
        val in = Input(UInt(inWidth.W))
        val roundingMode = Input(UInt(3.W))
        val overflow = Output(Bool())
        val out = Output(UInt(outWidth.W))
    })
    val sign = io.in(inWidth - 1) //符号位
    val round_bit = io.in(inWidth - outWidth - 1)  //关键舍入bit，结合sticky_bit判断有没有大于0.5
    val sticky_bit = (io.in(inWidth - outWidth - 1 - 1, 0) =/= 0.U)  //关键舍入bit后的bit是否为0
    val may_overflow = io.in(inWidth - 2, inWidth - outWidth) === Fill(outWidth - 1,1.U) //
    val out_buffer = Wire(UInt(outWidth.W))
    io.overflow := false.B
    when(io.roundingMode === round_near_even)
    {
        //四舍六入，奇进偶不进        
        when(round_bit && (sticky_bit || (io.in(inWidth - outWidth) === 1.U)))  //要进位
        {
            when(may_overflow && (~sign))  //正数且溢出
            {
                out_buffer := io.in(inWidth - 1, inWidth - outWidth)  //饱和了
                io.overflow := true.B
            }.otherwise{
                out_buffer := (io.in(inWidth - 1, inWidth - outWidth) + 1.U)
            }
        }.otherwise
        {
            out_buffer := io.in(inWidth - 1, inWidth - outWidth)
        }
    }.elsewhen(io.roundingMode === round_minMag)
    {
        //向零舍入
        when(~sign)
        {
            out_buffer := io.in(inWidth - 1, inWidth - outWidth)
        }.otherwise
        {
            out_buffer := io.in(inWidth - 1, inWidth - outWidth) + 1.U
        }
    }.elsewhen(io.roundingMode === round_min)
    {
        //向负无穷舍入
        out_buffer := io.in(inWidth - 1, inWidth - outWidth)

    }.elsewhen(io.roundingMode === round_max)
    {
        //向正无穷舍入
        when(may_overflow && (~sign))
        {
            out_buffer := io.in(inWidth - 1, inWidth - outWidth)  //饱和了
            io.overflow := true.B
        }.otherwise
        {
            out_buffer := (io.in(inWidth - 1, inWidth - outWidth) + 1.U)
        }
    }.elsewhen(io.roundingMode === round_near_maxMag)
    {
        //向绝对值无穷舍入
        when(sign)
        {
            when(may_overflow)
            {
                out_buffer := io.in(inWidth - 1, inWidth - outWidth)  //饱和了
                io.overflow := true.B
            }.otherwise{
                out_buffer := io.in(inWidth - 1, inWidth - outWidth) + 1.U
            }
        }.otherwise
        {
            out_buffer := io.in(inWidth - 1, inWidth - outWidth)
        }
    }.elsewhen(io.roundingMode === round_odd)
    {
        //奇数舍入
        when(round_bit && (sticky_bit || (io.in(inWidth - outWidth) === 0.U)))
        {
            when(may_overflow && (~sign))
            {
                out_buffer := io.in(inWidth - 1, inWidth - outWidth)  //饱和了
                io.overflow := true.B
            }.otherwise{
                out_buffer := (io.in(inWidth - 1, inWidth - outWidth) + 1.U)
            }
        }.otherwise
        {
            out_buffer := io.in(inWidth - 1, inWidth - outWidth)
        }
    }.otherwise
    {
        out_buffer := io.in(inWidth - 1, inWidth - outWidth)
    }
    io.out := Mux((round_bit ## sticky_bit) === 0.U, io.in(inWidth - 1, inWidth - outWidth), out_buffer) //舍去部分全为0则直接截取
}
