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
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

=============================================================================*/

package invsqrtutils

import chisel3._
import chisel3.util._
import consts._


//----------------------------------------------------------------------------
//----------------------------------------------------------------------------
class AddRawFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val subOp = Input(Bool())
        val a = Input(new RawFloat(expWidth, sigWidth))
        val b = Input(new RawFloat(expWidth, sigWidth))
        val roundingMode = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        val invalidExc = Output(Bool())
        val rawOut = Output(new RawFloat(expWidth, sigWidth + 2))
        val out_roundingMode = Output(UInt(3.W))
        val out_detectTininess = Output(Bool())
        //val debug_test_align_add = Output(Bool())
        //val debug_test_add_sel = Output(Bool())

    })

    // === 1. 输入信号准备与特殊情况检测阶段 ===
    val alignDistWidth = log2Ceil(sigWidth) // 1 2 3
    val effSignB = io.b.sign ^ io.subOp // 1 2 3 4
    val eqSigns = io.a.sign === effSignB // 1 2 3 4
    val notEqSigns_signZero = io.roundingMode === round_min // 1 2 3 4
    val sDiffExps = io.a.sExp - io.b.sExp // 1 2 3 4
    val modNatAlignDist = Mux(sDiffExps < 0.S, io.b.sExp - io.a.sExp, sDiffExps)(alignDistWidth - 1, 0) // 1
    val isMaxAlign =
        (sDiffExps>>alignDistWidth) =/= 0.S &&
            ((sDiffExps>>alignDistWidth) =/= -1.S || sDiffExps(alignDistWidth - 1, 0) === 0.U) // 1
    val alignDist = Mux(isMaxAlign, ((BigInt(1)<<alignDistWidth) - 1).U, modNatAlignDist) // 1 2
    val closeSubMags = !eqSigns && !isMaxAlign && (modNatAlignDist <= 1.U) // 1 2 3 4

    // === 2. 尾数对齐阶段 ===
    val close_alignedSigA =
        Mux((0.S <= sDiffExps) &&  sDiffExps(0), io.a.sig<<2, 0.U) |
        Mux((0.S <= sDiffExps) && !sDiffExps(0), io.a.sig<<1, 0.U) |
        Mux((sDiffExps < 0.S)                  , io.a.sig,    0.U) // 2 3
    val far_sigLarger  = Mux(sDiffExps < 0.S, io.b.sig, io.a.sig)(sigWidth - 1, 0) // 2 3
    val far_sigSmaller = Mux(sDiffExps < 0.S, io.a.sig, io.b.sig)(sigWidth - 1, 0) // 2
    val far_mainAlignedSigSmaller = (far_sigSmaller<<5)>>alignDist // 2
    val far_reduced4SigSmaller = orReduceBy4(far_sigSmaller<<2) // 2
    val far_roundExtraMask = lowMask(alignDist(alignDistWidth - 1, 2), (sigWidth + 5)/4, 0) // 2
    val far_alignedSigSmaller =
        Cat(far_mainAlignedSigSmaller>>3,
            far_mainAlignedSigSmaller(2, 0).orR || (far_reduced4SigSmaller & far_roundExtraMask).orR) // 2 3

    // === align/addi 段间寄存器 ===

    val add_a = RegNext(io.a)
    val add_b = RegNext(io.b)
    val add_effSignB = RegNext(effSignB) //0.05
    val add_eqSigns = RegNext(eqSigns)      //0.08
    val add_notEqSigns_signZero = RegNext(notEqSigns_signZero) //0.05
    val add_sDiffExps = RegNext(sDiffExps) //0.05
    val add_closeSubMags = RegNext(closeSubMags) //0.7
    val add_close_alignedSigA = RegNext(close_alignedSigA)
    val add_far_sigLarger = RegNext(far_sigLarger)
    val add_far_alignedSigSmaller = RegNext(far_alignedSigSmaller)
    val add_roundingMode = RegNext(io.roundingMode)
    val add_detectTininess = RegNext(io.detectTininess)

    // === 3. 尾数加减阶段 ===
    val close_sSigSum = add_close_alignedSigA.asSInt - (add_b.sig<<1).asSInt // 3 4
    val close_sigSum = Mux(close_sSigSum < 0.S, -close_sSigSum, close_sSigSum)(sigWidth + 1, 0) // 3 4
    val close_adjustedSigSum = close_sigSum<<(sigWidth & 1) // 3 4
    val close_reduced2SigSum = orReduceBy2(close_adjustedSigSum) // 3 4
    val close_normDistReduced2 = countLeadingZeros(close_reduced2SigSum) // 3 4
    val close_nearNormDist = (close_normDistReduced2<<1)(alignDistWidth - 1, 0) // 3 4
    val close_sigOut = ((close_sigSum<<close_nearNormDist)<<1)(sigWidth + 2, 0) // 3 4
    val close_totalCancellation = !(close_sigOut((sigWidth + 2), (sigWidth + 1)).orR) // 3 4
    val close_notTotalCancellation_signOut = add_a.sign ^ (close_sSigSum < 0.S) // 3 4

    val far_subMags = !add_eqSigns // 3 4
    val far_negAlignedSigSmaller = Mux(far_subMags, Cat(1.U, ~add_far_alignedSigSmaller), add_far_alignedSigSmaller) // 3 4
    val far_sigSum = (add_far_sigLarger<<3) + far_negAlignedSigSmaller + far_subMags // 3 4
    val far_signOut = Mux(add_sDiffExps < 0.S, add_effSignB, add_a.sign) // 3 4
    val far_sigOut = Mux(far_subMags, far_sigSum, (far_sigSum>>1) | far_sigSum(0))(sigWidth + 2, 0) // 3 4


    // === add/sel 段间寄存器 ===
    val sel_a = RegNext(add_a)
    val sel_b = RegNext(add_b)
    val sel_effSignB = RegNext(add_effSignB)
    val sel_eqSigns = RegNext(add_eqSigns)
    val sel_notEqSigns_signZero = RegNext(add_notEqSigns_signZero)
    val sel_sDiffExps = RegNext(add_sDiffExps)
    val sel_closeSubMags = RegNext(add_closeSubMags)
    val sel_close_alignedSigA = RegNext(add_close_alignedSigA)

    val sel_close_sSigSum = RegNext(close_sSigSum)
    val sel_close_sigSum = RegNext(close_sigSum)
    val sel_close_adjustedSigSum = RegNext(close_adjustedSigSum)
    val sel_close_reduced2SigSum = RegNext(close_reduced2SigSum)
    val sel_close_normDistReduced2 = RegNext(close_normDistReduced2)
    val sel_close_nearNormDist = RegNext(close_nearNormDist)
    val sel_close_sigOut = RegNext(close_sigOut)
    val sel_close_totalCancellation = RegNext(close_totalCancellation)
    val sel_close_notTotalCancellation_signOut = RegNext(close_notTotalCancellation_signOut)

    val sel_far_subMags = RegNext(far_subMags)
    val sel_far_negAlignedSigSmaller = RegNext(far_negAlignedSigSmaller)
    val sel_far_sigSum = RegNext(far_sigSum)
    val sel_far_signOut = RegNext(far_signOut)
    val sel_far_sigOut = RegNext(far_sigOut)

    val sel_roundingMode = RegNext(add_roundingMode)
    val sel_detectTininess = RegNext(add_detectTininess)

    // === 4. 结果选择与特殊情况处理阶段 ===
    val notSigNaN_invalidExc = sel_a.isInf && sel_b.isInf && !sel_eqSigns // 4 5
    val notNaN_isInfOut = sel_a.isInf || sel_b.isInf // 4 5
    val addZeros = sel_a.isZero && sel_b.isZero // 4 5
    val notNaN_specialCase = notNaN_isInfOut || addZeros // 4 5
    val notNaN_isZeroOut = addZeros || (!notNaN_isInfOut && sel_closeSubMags && sel_close_totalCancellation) // 4 5
    val notNaN_signOut =
        (sel_eqSigns                      && sel_a.sign          ) ||
        (sel_a.isInf                   && sel_a.sign          ) ||
        (sel_b.isInf                   && sel_effSignB           ) ||
        (notNaN_isZeroOut && !sel_eqSigns && sel_notEqSigns_signZero) ||
        (!notNaN_specialCase && sel_closeSubMags && !sel_close_totalCancellation
                                     && sel_close_notTotalCancellation_signOut) ||
        (!notNaN_specialCase && !sel_closeSubMags && sel_far_signOut) // 4 5
    val common_sExpOut =
        (Mux(sel_closeSubMags || (sel_sDiffExps < 0.S), sel_b.sExp, sel_a.sExp)
            - Mux(sel_closeSubMags, sel_close_nearNormDist, sel_far_subMags).zext) // 4 5
    val common_sigOut = Mux(sel_closeSubMags, sel_close_sigOut, sel_far_sigOut) // 4 5

    // === 5. 输出打包阶段 ===
    io.invalidExc := isSigNaNRawFloat(sel_a) || isSigNaNRawFloat(sel_b) || notSigNaN_invalidExc // 5
    io.rawOut.isInf := notNaN_isInfOut // 5
    io.rawOut.isZero := notNaN_isZeroOut // 5
    io.rawOut.sExp := common_sExpOut // 5
    io.rawOut.isNaN := sel_a.isNaN || sel_b.isNaN // 5
    io.rawOut.sign := notNaN_signOut // 5
    io.rawOut.sig := common_sigOut // 5

    io.out_roundingMode := sel_roundingMode
    io.out_detectTininess := sel_detectTininess
}

//----------------------------------------------------------------------------
//----------------------------------------------------------------------------

class AddRecFN(expWidth: Int, sigWidth: Int) extends Module
{
    val io = IO(new Bundle {
        val subOp = Input(Bool())
        val a = Input(UInt((expWidth + sigWidth + 1).W))
        val b = Input(UInt((expWidth + sigWidth + 1).W))
        val roundingMode = Input(UInt(3.W))
        val detectTininess = Input(Bool())
        val out = Output(UInt((expWidth + sigWidth + 1).W))
        val exceptionFlags = Output(UInt(5.W))
    })

    val ins_subOp = RegNext(io.subOp)
    val ins_a = RegNext(io.a)
    val ins_b = RegNext(io.b)
    val ins_roundingMode = RegNext(io.roundingMode)
    val ins_detectTininess = RegNext(io.detectTininess)

    val rawA = rawFloatFromRecFN(expWidth, sigWidth, ins_a)
    val rawB = rawFloatFromRecFN(expWidth, sigWidth, ins_b)

    val addRawFN = Module(new AddRawFN(expWidth, sigWidth))
    addRawFN.io.subOp        := ins_subOp
    addRawFN.io.a            := rawA
    addRawFN.io.b            := rawB
    addRawFN.io.roundingMode := ins_roundingMode
    addRawFN.io.detectTininess := ins_detectTininess

    val roundRawFNToRecFN =
        Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    roundRawFNToRecFN.io.invalidExc   := addRawFN.io.invalidExc
    roundRawFNToRecFN.io.infiniteExc  := false.B
    roundRawFNToRecFN.io.in           := addRawFN.io.rawOut
    roundRawFNToRecFN.io.roundingMode := addRawFN.io.out_roundingMode
    roundRawFNToRecFN.io.detectTininess := addRawFN.io.out_detectTininess

    io.out            := roundRawFNToRecFN.io.out
    io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}

