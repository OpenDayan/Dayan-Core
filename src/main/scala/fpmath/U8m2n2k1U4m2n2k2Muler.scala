package fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class U8m2n2k1U4m2n2k2Muler extends Module{
  val a_in = IO(Input(UInt(16.W))) // Packed m2k2-U4 or m2k1-U8 (m,k)
  val b_in = IO(Input(UInt(16.W))) // Packed n2k2-U4 or n2k1-U8 (n,k)
  val u8 = IO(Input(Bool()))

  val u4_out = IO(Output(Vec(2, Vec(2, Vec(2, UInt(8.W))))))  // m2n2k2-U8
  val u8_out = IO(Output(Vec(2, Vec(2, UInt(16.W))))) // m2n2-U8



}
