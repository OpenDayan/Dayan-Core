package rvtng.vpu

import rcputils._

import chisel3._
import chisel3.util._
import consts._
import chisel3.dontTouch

import chisel3.util.HasBlackBoxResource
import chisel3.experimental._
import scala.collection.immutable
import scala.math._
import scala.util.control.Breaks._

//----------------------------------------------------------------------------
//----------------------------------------------------------------------------
class RcpRawFN(expWidth: Int, sigWidth: Int) extends Module //8,24
{
    val io = IO(new Bundle {
        val a              = Input(UInt((expWidth + sigWidth).W))
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        val exceptionFlags = Output(UInt(5.W))
        val out            = Output(UInt((expWidth + sigWidth).W))
        val too_small      = Output(Bool())
        val is_inf         = Output(Bool())
    })
    /*------------------------------------------------------------------------
    初始数据准备模块
    *------------------------------------------------------------------------*/
    val numerator  = "b0_01111111_00000000000000000000000".U //1
    val constant1  = "b0_10000000_01101001011010010110101".U;//48/17;
    val constant2  = "b1_01111111_11100001111000011110001".U;//-32/17;

    // val a_RawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    // a_RawFNToRecFN.io.invalidExc   := isSigNaNRawFloat(io.a)
    // a_RawFNToRecFN.io.infiniteExc  := false.B
    // //a_RawFNToRecFN.io.in           := io.a
    // a_RawFNToRecFN.io.in.isInf     := io.a.isInf 
    // a_RawFNToRecFN.io.in.isNaN     := io.a.isNaN 
    // a_RawFNToRecFN.io.in.isZero    := io.a.isZero 
    // a_RawFNToRecFN.io.in.sign      := io.a.sign 
    // a_RawFNToRecFN.io.in.sExp      := io.a.sExp 
    // a_RawFNToRecFN.io.in.sig       := io.a.sig ## 0.U(2.W)
    // a_RawFNToRecFN.io.roundingMode := io.roundingMode
    // a_RawFNToRecFN.io.detectTininess := io.detectTininess
    // val a_rec          = a_RawFNToRecFN.io.out
    // val a_RawFNToRecFN_exceptionFlags = a_RawFNToRecFN.io.exceptionFlags

    
    // val a_FN       = fNFromRecFN(expWidth, sigWidth, a_rec)
    val a_FN       = io.a
    val a_FN_pos   = 0.U(1.W) ## a_FN(expWidth + sigWidth - 2, 0)  //都当作正数处理
    val numerator1 = Wire(UInt((expWidth + sigWidth).W))
    val D          = Wire(UInt((expWidth + sigWidth).W))

    //小于最小能处理的数0.25*2^-126时
    val noreg_before_too_small = Wire(Bool())
    when(a_FN_pos(expWidth + sigWidth - 2, 0) < "b0_00000000_01000000000000000000000".U) 
    {
        // io.too_small := true.B
        noreg_before_too_small := true.B
    }.otherwise
    {
        // io.too_small := false.B
        noreg_before_too_small := false.B
    }
    //判断输入是否是±inf
    val noreg_before_is_inf = Wire(Bool())
    when((a_FN_pos(expWidth + sigWidth - 2, sigWidth - 1) === "b11111111".U) && (a_FN_pos(sigWidth - 2, 0) === 0.U)) 
    {
        // io.is_inf := true.B
        noreg_before_is_inf := true.B
    }.otherwise
    {
        // io.is_inf := false.B
        noreg_before_is_inf := false.B
    }
    //判断输入数的指数位是否位253，254
    when(a_FN_pos(expWidth + sigWidth - 2, sigWidth - 1) === 253.U)
    {
        numerator1 := "b0_0000_0000_1000_0000_0000_0000_0000_000".U(32.W)
    }.elsewhen(a_FN_pos(expWidth + sigWidth - 2, sigWidth - 1) === 254.U)
    {
        numerator1 := "b0_0000_0000_0100_0000_0000_0000_0000_000".U(32.W)
    }.otherwise
    {
        numerator1 := 0.U(1.W) ## (numerator(expWidth + sigWidth - 2,sigWidth - 1) - 1.U - a_FN_pos(expWidth + sigWidth - 2,sigWidth - 1) + 127.U) ## numerator(sigWidth - 2,0)
    }

    //当a是subnormal的数时，对于d1数需要进行乘以2减1的处理
    val D_buffer = 0.U(1.W) ## "b0111_1111".U ## a_FN_pos(sigWidth - 2,0)
    val addFN_subnormal = Module(new FP32Adder())
    addFN_subnormal.io.subOp          := true.B;
    addFN_subnormal.io.a              := D_buffer
    addFN_subnormal.io.b              := "h3F800000".U
    addFN_subnormal.io.detectTininess := io.detectTininess
    addFN_subnormal.io.roundingMode   := io.roundingMode
    val D_subnormal_buffer             = addFN_subnormal.io.out
    val D_subnormal_exceptionFlags     = addFN_subnormal.io.exceptionFlags
    when(RegNext(RegNext(RegNext(a_FN_pos(expWidth + sigWidth - 2, sigWidth - 1)))) === 0.U) 
    {
        D := D_subnormal_buffer
    }.otherwise
    {
        D := 0.U(1.W) ## "b0111_1110".U ## RegNext(RegNext(RegNext(a_FN_pos(sigWidth - 2,0))))
    }

    /*------------------------------------------------------------------------
    第一级流水，给too_small，is_inf，D，numerator1，exceptionFlags，sign加寄存器流水级
    *------------------------------------------------------------------------*/
    val reg0_too_small  = RegNext(RegNext(RegNext(noreg_before_too_small)))
    val reg0_is_inf     = RegNext(RegNext(RegNext(noreg_before_is_inf)))
    val reg0_sign       = RegNext(RegNext(RegNext(a_FN(expWidth + sigWidth - 1))))
    val reg0_numerator1 = RegNext(RegNext(RegNext(numerator1)))

    /*------------------------------------------------------------------------
    核心计算迭代模块，方法采用牛顿迭代法
    *------------------------------------------------------------------------*/
    //计算-32/17 * D
    val mulFN_D = Module(new FP32Multiplier())
    // mulFN_D.io.a              := D 
    mulFN_D.io.a              := D
    mulFN_D.io.b              := constant2
    mulFN_D.io.detectTininess := io.detectTininess
    mulFN_D.io.roundingMode   := io.roundingMode
    val mulFN_D_x0             = mulFN_D.io.out
    val mulFN_D_exceptionFlags = mulFN_D.io.exceptionFlags

    //加一级流水
    val reg1_too_small      = RegNext(RegNext(reg0_too_small))
    val reg1_is_inf         = RegNext(RegNext(reg0_is_inf))
    val reg1_sign           = RegNext(RegNext(reg0_sign))
    val reg1_numerator1     = RegNext(RegNext(reg0_numerator1))
    val reg1_D              = RegNext(RegNext(D))

    val reg1_exceptionFlags = mulFN_D_exceptionFlags | RegNext(RegNext(D_subnormal_exceptionFlags))

    //计算48/17-32/17 * D
    val addFN_D = Module(new FP32Adder())
    addFN_D.io.subOp          := false.B;
    // addFN_D.io.a              := mulFN_D_x0
    addFN_D.io.a              := mulFN_D_x0
    addFN_D.io.b              := constant1
    addFN_D.io.detectTininess := io.detectTininess
    addFN_D.io.roundingMode   := io.roundingMode
    val addFN_D_x0add          = addFN_D.io.out
    val addFN_D_exceptionFlags = addFN_D.io.exceptionFlags

    /*------------------------------------------------------------------------
    第二级流水，给too_small，is_inf，numerator1，D，48/17-32/17 * D，exceptionFlags，sign加寄存器流水级
    *------------------------------------------------------------------------*/
    val reg2_too_small  = RegNext(RegNext(RegNext(reg1_too_small)))
    val reg2_is_inf     = RegNext(RegNext(RegNext(reg1_is_inf)))
    val reg2_sign       = RegNext(RegNext(RegNext(reg1_sign)))
    val reg2_numerator1 = RegNext(RegNext(RegNext(reg1_numerator1)))
    val reg2_D          = RegNext(RegNext(RegNext(reg1_D)))

    val reg2_exceptionFlags = addFN_D_exceptionFlags | RegNext(RegNext(RegNext(reg1_exceptionFlags)))

    //计算Xn+1 = Xn * (2 - D * Xn)，迭代次数越多，结果越精确  
    //迭代第1轮 
    val iter1                = Module(new XnCalculationFN(expWidth, sigWidth))
    // iter1.io.X              := addFN_D_x0add
    // iter1.io.D              := D
    iter1.io.X              := addFN_D_x0add
    iter1.io.D              := reg2_D
    iter1.io.detectTininess := io.detectTininess
    iter1.io.roundingMode   := io.roundingMode
    val iter1_out            = iter1.io.out
    val iter1_exceptionFlags = iter1.io.exceptionFlags

    //加一级流水
    val reg3_too_small  = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg2_too_small)))))))
    val reg3_is_inf     = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg2_is_inf)))))))
    val reg3_sign       = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg2_sign)))))))
    val reg3_numerator1 = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg2_numerator1)))))))
    val reg3_D          = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg2_D)))))))

    val reg3_exceptionFlags = iter1_exceptionFlags | RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg2_exceptionFlags)))))))

    //迭代第2轮 
    val iter2 = Module(new XnCalculationFN(expWidth, sigWidth))
    iter2.io.X              := iter1_out
    iter2.io.D              := reg3_D
    iter2.io.detectTininess := io.detectTininess
    iter2.io.roundingMode   := io.roundingMode
    val iter2_out            = iter2.io.out
    val iter2_exceptionFlags = iter2.io.exceptionFlags

    //加一级流水
    val reg4_too_small  = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg3_too_small)))))))
    val reg4_is_inf     = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg3_is_inf)))))))
    val reg4_sign       = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg3_sign)))))))
    val reg4_numerator1 = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg3_numerator1)))))))
    val reg4_D          = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg3_D)))))))

    val reg4_exceptionFlags = iter2_exceptionFlags | RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg3_exceptionFlags)))))))

    //迭代第3轮 
    val iter3 = Module(new XnCalculationFN(expWidth, sigWidth))
    iter3.io.X              := iter2_out
    iter3.io.D              := reg4_D
    iter3.io.detectTininess := io.detectTininess
    iter3.io.roundingMode   := io.roundingMode
    val iter3_out            = iter3.io.out
    val iter3_exceptionFlags = iter3.io.exceptionFlags

    //加一级流水
    val reg5_too_small  = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg4_too_small)))))))
    val reg5_is_inf     = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg4_is_inf)))))))
    val reg5_sign       = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg4_sign)))))))
    val reg5_numerator1 = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg4_numerator1)))))))
    val reg5_D          = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg4_D)))))))

    val reg5_exceptionFlags = iter3_exceptionFlags | RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg4_exceptionFlags)))))))

    //迭代第4轮 
    val iter4 = Module(new XnCalculationFN(expWidth, sigWidth))
    iter4.io.X              := iter3_out
    iter4.io.D              := reg5_D
    iter4.io.detectTininess := io.detectTininess
    iter4.io.roundingMode   := io.roundingMode
    val iter4_out            = iter4.io.out
    val iter4_exceptionFlags = iter4.io.exceptionFlags
    
    //加一级流水
    val reg6_too_small  = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg5_too_small)))))))
    val reg6_is_inf     = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg5_is_inf)))))))
    val reg6_sign       = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg5_sign)))))))
    val reg6_numerator1 = RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg5_numerator1)))))))

    val reg6_exceptionFlags = iter4_exceptionFlags | RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(RegNext(reg5_exceptionFlags)))))))

    //迭代出来的值Xn，乘以numerator1得到倒数结果
    val mul_numerator = Module(new FP32Multiplier())
    mul_numerator.io.a              := iter4_out 
    mul_numerator.io.b              := reg6_numerator1
    mul_numerator.io.detectTininess := io.detectTininess
    mul_numerator.io.roundingMode   := io.roundingMode

    //加一级流水
    val reg7_too_small  = RegNext(RegNext(reg6_too_small))
    val reg7_is_inf     = RegNext(RegNext(reg6_is_inf))
    val reg7_sign       = RegNext(RegNext(reg6_sign))

    val reg7_exceptionFlags = mul_numerator.io.exceptionFlags | RegNext(RegNext(reg6_exceptionFlags))

    //输出
    io.out := reg7_sign ## mul_numerator.io.out(expWidth + sigWidth - 2, 0)
    io.exceptionFlags := reg7_exceptionFlags
    io.too_small := reg7_too_small
    io.is_inf    := reg7_is_inf
    
    /*------------------------------------------------------------------------
    *------------------------------------------------------------------------*/
    //io.invalidExc := isSigNaNRawFloat(io.a)
}
//----------------------------------------------------------------------------
//iter一共7级流水
//----------------------------------------------------------------------------
class XnCalculationFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val X              = Input(UInt((expWidth + sigWidth).W))
        val D              = Input(UInt((expWidth + sigWidth).W))
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        val out            = Output(UInt((expWidth + sigWidth).W))
        val exceptionFlags = Output(UInt(5.W))
    })
    val two  = "b0_10000000_00000000000000000000000".U //常数2
    val mulFN_1 = Module(new FP32Multiplier())
    mulFN_1.io.a              := io.X
    mulFN_1.io.b              := io.D
    mulFN_1.io.detectTininess := io.detectTininess
    mulFN_1.io.roundingMode   := io.roundingMode
    val mulFN_1_out            = mulFN_1.io.out
    val mulFN_1_exceptionFlags = mulFN_1.io.exceptionFlags

    // //加一级流水
    val reg0_X                      = RegNext(RegNext(io.X))

    val addFN_1 = Module(new FP32Adder())
    addFN_1.io.subOp          := true.B;
    addFN_1.io.a              := two
    addFN_1.io.b              := mulFN_1_out
    addFN_1.io.detectTininess := io.detectTininess
    addFN_1.io.roundingMode   := io.roundingMode
    val addFN_1_out            = addFN_1.io.out
    val addFN_1_exceptionFlags = addFN_1.io.exceptionFlags

    //加一级流水
    val reg1_addFN_1_exceptionFlags = addFN_1_exceptionFlags | RegNext(RegNext(RegNext(mulFN_1_exceptionFlags)))
    val reg1_X = RegNext(RegNext(RegNext(reg0_X)))

    val mulFN_2 = Module(new FP32Multiplier())
    // mulFN_2.io.a              := io.X
    // mulFN_2.io.b              := addFN_1_out
    mulFN_2.io.a              := reg1_X
    mulFN_2.io.b              := addFN_1_out
    mulFN_2.io.detectTininess := io.detectTininess
    mulFN_2.io.roundingMode   := io.roundingMode
    io.out                    := mulFN_2.io.out
    io.exceptionFlags         := mulFN_2.io.exceptionFlags | RegNext(RegNext(reg1_addFN_1_exceptionFlags))
}

//----------------------------------------------------------------------------
//5个输入，分别是加减选择、FN数a，FN数b，四舍五入模式，微小值舍入前后检测
//----------------------------------------------------------------------------
class RcpFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val in_rcp         = Input(UInt((expWidth + sigWidth).W)) //9+23+1=33
        val roundingMode   = Input(UInt(3.W))
        val detectTininess = Input(UInt(1.W)) //微小值舍入前后检测
        val out_rcp        = Output(UInt((expWidth + sigWidth).W)) //33
        val exceptionFlags = Output(UInt(5.W))
    })
    //------------------------------------------------------------------------
    //------------------------------------------------------------------------
    val rcpRawFN = Module(new RcpRawFN(expWidth, sigWidth))
    // rcpRawFN.io.a            := rawFloatFromFN(expWidth, sigWidth, io.in_rcp)
    rcpRawFN.io.a              := io.in_rcp
    rcpRawFN.io.roundingMode   := io.roundingMode
    rcpRawFN.io.detectTininess := io.detectTininess

    when(rcpRawFN.io.too_small === true.B)
    {
        // io.out_rcp            := round_out_fp(expWidth + sigWidth - 1) ## "b1111_1111_000_0000_0000_0000_0000_0000".U(31.W)
        io.out_rcp            := rcpRawFN.io.out(expWidth + sigWidth - 1) ## "b1111_1111_000_0000_0000_0000_0000_0000".U(31.W)
    }.elsewhen(rcpRawFN.io.is_inf === true.B) ////当数据是正负inf时，直接输出±0
    {
        // io.out_rcp            := round_out_fp(expWidth + sigWidth - 1) ## "b0000_0000_000_0000_0000_0000_0000_0000".U(31.W)
        io.out_rcp            := rcpRawFN.io.out(expWidth + sigWidth - 1) ## "b0000_0000_000_0000_0000_0000_0000_0000".U(31.W)
    }.otherwise
    {
        // io.out_rcp            := round_out_fp
        io.out_rcp            := rcpRawFN.io.out
    }   
    // io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags | rcpRawFN.io.exceptionFlags
    io.exceptionFlags := rcpRawFN.io.exceptionFlags
}
