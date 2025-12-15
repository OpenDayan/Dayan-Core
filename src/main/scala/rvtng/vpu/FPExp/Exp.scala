package fpexputils

import chisel3._
import chisel3.util._

class Exp extends Module {
    val io = IO(new Bundle {
        val ufixX_i = Input(UInt(34.W))
        val XSign   = Input(Bool())
        val expY    = Output(UInt(28.W))
        val K       = Output(UInt(9.W))
    })

    // --- Instantiate all submodules ---
    val mulInvLog2         = Module(new FixRealKCM_Freq150_uid8)
    val mulLog2            = Module(new FixRealKCM_Freq150_uid20)
    val theYAdder          = Module(new IntAdder_27_Freq150_uid33)
    val expATable          = Module(new FixFunctionByTable_Freq150_uid35)
    val expZmZm1Table      = Module(new FixFunctionByTable_Freq150_uid37)
    val adder_expZm1       = Module(new IntAdder_18_Freq150_uid41)
    val adder_expArounded0 = Module(new IntAdder_18_Freq150_uid45)
    val theLowerProduct    = Module(new IntMultiplier_17x18_19_Freq150_uid47)
    val theFinalAdder      = Module(new IntAdder_28_Freq150_uid351)

    // --- Logic and Connections ---

    // Pipeline register for XSign
    val xsign_d1 = RegNext(io.XSign)

    val ufixX  = io.ufixX_i
    val xMulIn = ufixX(33, 24)

    // MulInvLog2: K = 1/log2(e) * log2(X)
    mulInvLog2.io.X := xMulIn
    val absK = mulInvLog2.io.R

    val minusAbsK = 0.U(9.W) - Cat(0.U(1.W), absK)
    io.K := Mux(xsign_d1, minusAbsK, Cat(0.U(1.W), absK))

    // MulLog2: absK * log2(e)
    mulLog2.io.X := absK
    val absKLog2 = mulLog2.io.R

    // theYAdder: Y = X*log2(e) - K*log2(e)
    val subOp1 = Mux(io.XSign, ~ufixX(26, 0), ufixX(26, 0))
    val subOp2 = Mux(xsign_d1, absKLog2(26, 0), ~absKLog2(26, 0))
    theYAdder.io.X   := subOp1
    theYAdder.io.Y   := subOp2
    theYAdder.io.Cin := true.B // Adder used for subtraction with 2's complement
    val y_adder_out = theYAdder.io.R

    // Decompose Y into integer part A and fractional part Z
    val a = y_adder_out(26, 17)
    val z = y_adder_out(16, 0)

    // ExpATable: compute exp(A) from a table
    expATable.io.X := a
    val expA = expATable.io.Y

    // ExpZmZm1Table: compute exp(Z)-Z-1 from a table
    val ztrunc = z(16, 10)
    expZmZm1Table.io.X := ztrunc
    val expZmZm1 = expZmZm1Table.io.Y

    // Adder_expZm1: compute Z + (exp(Z)-1-Z) to approximate exp(Z)-1
    val expZm1adderX = Cat(0.U(1.W), z)
    val expZm1adderY = Cat(0.U(12.W), expZmZm1)
    adder_expZm1.io.X   := expZm1adderX
    adder_expZm1.io.Y   := expZm1adderY
    adder_expZm1.io.Cin := false.B
    val expZm1_res = adder_expZm1.io.R

    // Adder_expArounded0: Round exp(A)
    val expA_T = expA(27, 10)
    adder_expArounded0.io.X   := expA_T
    adder_expArounded0.io.Y   := 0.U(18.W)
    adder_expArounded0.io.Cin := true.B // Add 1 for rounding
    val expArounded0 = adder_expArounded0.io.R
    val expArounded  = expArounded0(17, 1)

    // TheLowerProduct: compute exp(A) * (exp(Z)-1)
    theLowerProduct.io.X := expArounded
    theLowerProduct.io.Y := expZm1_res
    val lowerProduct = theLowerProduct.io.R

    // TheFinalAdder: compute exp(A) + exp(A)*(exp(Z)-1) = exp(Y)
    val extendedLowerProduct = Cat(0.U(9.W), lowerProduct)
    theFinalAdder.io.X   := expA
    theFinalAdder.io.Y   := extendedLowerProduct
    theFinalAdder.io.Cin := false.B
    io.expY              := theFinalAdder.io.R
}
