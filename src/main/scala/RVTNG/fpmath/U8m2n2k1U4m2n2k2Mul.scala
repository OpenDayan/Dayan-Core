package RVTNG.fpmath

import RVTNG.utils.UnsignedMulBase
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class U8m2n2k1U4m2n2k2Mul(tM: Int, tN: Int, tK: Int) extends Module with U8U4FusedMxNyKzMul{
  require(tM == 2)
  require(tN == 2)
  require(tK == 2)
  val latency = 2
  val a_u4 = IO(Input(Vec(2, Vec(2, UInt(4.W)))))
  val b_u4 = IO(Input(Vec(2, Vec(2, UInt(4.W)))))
  val a_u8 = IO(Input(Vec(2, UInt(8.W))))
  val b_u8 = IO(Input(Vec(2, UInt(8.W))))
  val valid = IO(Input(Bool()))
  val u8 = IO(Input(Bool()))
  val u4_out = IO(Output(Vec(2, Vec(2, Vec(2, UInt(8.W)))))) // m2n2k2-U8
  val u8_out = IO(Output(Vec(2, Vec(2, UInt(16.W)))))        // m2n2-U16

  // Prepare Multiplicand
  // (m/n, k)
  // U8:
  //   Mul0: (a(1,0), 0.U(8.W), a(0,0)) * b(0,0) = (c(1,0), c(0,0))
  //   Mul1: (a(1,0), 0.U(8.W), a(0,0)) * b(1,0) = (c(1,1), c(0,1)))
  // U4:
  //   Mul0: (a(1,0), 0.U(12.W), a(0,0)) * (b(1,0), 0.U(4.W), b(0,0)) = (c0(1,1), c0(1,0), c0(0,1), c0(0,0))
  //   Mul1: (a(1,1), 0.U(12.W), a(0,1)) * (b(1,1), 0.U(4.W), b(0,1)) = (c1(1,1), c1(1,0), c1(0,1), c1(0,0))
  val mul_a_s1 = Reg(Vec(2,UInt(26.W)))
  val mul_b_s1 = Reg(Vec(2,UInt(17.W)))
  when(u8 && valid) {
    mul_a_s1(0) := Cat(0.U, a_u8(1), 0.U(8.W), a_u8(0))
    mul_a_s1(1) := Cat(0.U, a_u8(1), 0.U(8.W), a_u8(0))
    mul_b_s1(0) := Cat(0.U, b_u8(0))
    mul_b_s1(1) := Cat(0.U, b_u8(1))
  }.elsewhen(valid) {
    mul_a_s1(0) := Cat(0.U, a_u4(1)(0), 0.U(12.W), a_u4(0)(0))
    mul_a_s1(1) := Cat(0.U, a_u4(1)(1), 0.U(12.W), a_u4(0)(1))
    mul_b_s1(0) := Cat(0.U, b_u4(1)(0), 0.U(4.W),  b_u4(0)(0))
    mul_b_s1(1) := Cat(0.U, b_u4(1)(1), 0.U(4.W),  b_u4(0)(1))
  }

  // Core Multiplier
  val mul_s2 = VecInit.tabulate(2)(i => {
//    val mul = mul_a_s1(i) * mul_b_s1(i)
    val mulCore = Module(new UnsignedMulBase(26, 17))
    mulCore.io.a := mul_a_s1(i)
    mulCore.io.b := mul_b_s1(i)
    RegNext(mulCore.io.c)
  })

  // Result View
  u4_out := VecInit.tabulate(2, 2, 2)((m, n, k) => {
    mul_s2(k)(16 * m + 8 * n + 7, 16 * m + 8 * n)
  })
  u8_out := VecInit.tabulate(2, 2)((m, n) => {
    mul_s2(n)(m * 16 + 15, m * 16)
  })

}

object U8m2n2k1U4m2n2k2Mul extends App {
  ChiselStage.emitSystemVerilog(
    new U8m2n2k1U4m2n2k2Mul(2, 2, 2),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/U8m2n2k1U4m2n2k2Mul"
    )
  )
}
