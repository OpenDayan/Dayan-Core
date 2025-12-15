/*============================================================================

This Chisel source file is part of a pre-release version of the HardFloat IEEE
Floating-Point Arithmetic Package, by John R. Hauser (ported from Verilog to
Chisel by Andrew Waterman).

Copyright 2019, 2020 The Regents of the University of California.  All rights
reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice,
    this list of conditions, and the following disclaimer.

 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions, and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 3. Neither the name of the University nor the names of its contributors may
    be used to endorse or promote products derived from this software without
    specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS", AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, ARE
DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

=============================================================================*/

package rvtng.vpu 

import chisel3._
import chisel3.util._
import hardfloat._
import hardfloat.consts._

//----------------------------------------------------------------------------

class fp32MulFullRawFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val a = Input(new RawFloat(expWidth, sigWidth))
        val b = Input(new RawFloat(expWidth, sigWidth))

        val roundingMode = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        
        val invalidExc = Output(Bool())
        val rawOut = Output(new RawFloat(expWidth, sigWidth*2 - 1))

        val outRoundingMode = Output(UInt(3.W))
        val outDetectTininess = Output(Bool())
    })

    //------------------------------------------------------------------------

    val notSigNaN_invalidExc = (io.a.isInf && io.b.isZero) || (io.a.isZero && io.b.isInf)
    val notNaN_isInfOut = io.a.isInf || io.b.isInf
    val notNaN_isZeroOut = io.a.isZero || io.b.isZero
    val notNaN_signOut = io.a.sign ^ io.b.sign
    val common_sExpOut = io.a.sExp + io.b.sExp - (1<<expWidth).S
    val isNaN = io.a.isNaN || io.b.isNaN
    val common_sigOut = (io.a.sig * io.b.sig)(sigWidth*2 - 1, 0)

    //------------------------------------------------------------------------

    class mulSegReg extends Bundle {
        val a = new RawFloat(expWidth, sigWidth)
        val b = new RawFloat(expWidth, sigWidth)
        val roundingMode = UInt(3.W)
        val detectTininess = Bool()
        val notSigNaN_invalidExc = Bool()
        val notNaN_isInfOut = Bool()
        val notNaN_isZeroOut = Bool()
        val notNaN_signOut = Bool()
        val common_sExpOut = SInt((expWidth+2).W)
        val isNaN = Bool()
        val common_sigOut = UInt((sigWidth*2).W)
    }

    val mulInSegReg = Wire(new mulSegReg)
    mulInSegReg.a := io.a
    mulInSegReg.b := io.b
    mulInSegReg.roundingMode := io.roundingMode
    mulInSegReg.detectTininess := io.detectTininess
    mulInSegReg.notSigNaN_invalidExc := notSigNaN_invalidExc
    mulInSegReg.notNaN_isInfOut := notNaN_isInfOut
    mulInSegReg.notNaN_isZeroOut := notNaN_isZeroOut
    mulInSegReg.notNaN_signOut := notNaN_signOut
    mulInSegReg.common_sExpOut := common_sExpOut
    mulInSegReg.isNaN := isNaN
    mulInSegReg.common_sigOut := common_sigOut

    val mulOutSegReg = RegNext(mulInSegReg)
    dontTouch(mulOutSegReg)
    //------------------------------------------------------------------------

    io.invalidExc := isSigNaNRawFloat(mulOutSegReg.a) || isSigNaNRawFloat(mulOutSegReg.b) || mulOutSegReg.notSigNaN_invalidExc
    io.rawOut.isInf := mulOutSegReg.notNaN_isInfOut
    io.rawOut.isZero := mulOutSegReg.notNaN_isZeroOut
    io.rawOut.sExp := mulOutSegReg.common_sExpOut
    io.rawOut.isNaN := mulOutSegReg.isNaN
    io.rawOut.sign := mulOutSegReg.notNaN_signOut
    io.rawOut.sig := mulOutSegReg.common_sigOut

    io.outRoundingMode := mulOutSegReg.roundingMode
    io.outDetectTininess := mulOutSegReg.detectTininess
}

class fp32MulRawFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val a = Input(new RawFloat(expWidth, sigWidth))
        val b = Input(new RawFloat(expWidth, sigWidth))

        val roundingMode = Input(UInt(3.W))
        val detectTininess = Input(Bool())

        val invalidExc = Output(Bool())
        val rawOut = Output(new RawFloat(expWidth, sigWidth + 2))

        val outRoundingMode = Output(UInt(3.W))
        val outDetectTininess = Output(Bool())
    })

    val mulFullRaw = Module(new fp32MulFullRawFN(expWidth, sigWidth))
    mulFullRaw.io.a := io.a
    mulFullRaw.io.b := io.b
    mulFullRaw.io.roundingMode := io.roundingMode
    mulFullRaw.io.detectTininess := io.detectTininess

    io.invalidExc := mulFullRaw.io.invalidExc
    io.rawOut := mulFullRaw.io.rawOut
    io.rawOut.sig := {
      val sig = mulFullRaw.io.rawOut.sig
      Cat(sig >> (sigWidth - 2), sig(sigWidth - 3, 0).orR)
    }

    io.outRoundingMode := mulFullRaw.io.outRoundingMode
    io.outDetectTininess := mulFullRaw.io.outDetectTininess
}

//----------------------------------------------------------------------------

class fp32MulRecFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val a = Input(UInt((expWidth + sigWidth + 1).W))
        val b = Input(UInt((expWidth + sigWidth + 1).W))
        val roundingMode = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        val out = Output(UInt((expWidth + sigWidth + 1).W))
        val exceptionFlags = Output(UInt(5.W))
    })

    //------------------------------------------------------------------------
    
    class inputBundle extends Bundle {
        val a = UInt((expWidth + sigWidth + 1).W)
        val b = UInt((expWidth + sigWidth + 1).W)
        val roundingMode = UInt(3.W)
        val detectTininess = Bool()
    }
    val out_input = Wire(new inputBundle)
    out_input.a := io.a
    out_input.b := io.b
    out_input.roundingMode := io.roundingMode
    out_input.detectTininess := io.detectTininess
    
    val ins_input = RegNext(out_input)
    dontTouch(ins_input)
    //------------------------------------------------------------------------
    
    val mulRawFN = Module(new fp32MulRawFN(expWidth, sigWidth))

    mulRawFN.io.a := rawFloatFromRecFN(expWidth, sigWidth, ins_input.a)
    mulRawFN.io.b := rawFloatFromRecFN(expWidth, sigWidth, ins_input.b)
    mulRawFN.io.roundingMode := ins_input.roundingMode
    mulRawFN.io.detectTininess := ins_input.detectTininess

    //------------------------------------------------------------------------

    val roundRawFNToRecFN =
        Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    roundRawFNToRecFN.io.invalidExc   := mulRawFN.io.invalidExc
    roundRawFNToRecFN.io.infiniteExc  := false.B
    roundRawFNToRecFN.io.in           := mulRawFN.io.rawOut
    roundRawFNToRecFN.io.roundingMode := mulRawFN.io.outRoundingMode
    roundRawFNToRecFN.io.detectTininess := mulRawFN.io.outDetectTininess
    io.out            := roundRawFNToRecFN.io.out
    io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}

