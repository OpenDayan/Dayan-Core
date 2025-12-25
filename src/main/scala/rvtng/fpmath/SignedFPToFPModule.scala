package rvtng.fpmath

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// TODO: Known limitation, this module cannot generate Sub-Normal Numbers
class SignedFPToFPModule(wE: Int, wF: Int, bias: Int) extends Module {
  val exp = IO(Input(SInt(wE.W)))
  val frac = IO(Input(UInt(wF.W)))
  val fp = IO(Output(UInt(32.W)))

  private val sign = frac(wF-1)
  private val fracAbs = Mux(sign.asBool, -frac, frac)

  private val clzFrac = CLZ(fracAbs)
  private val trueFrac = fracAbs << clzFrac
  private val addedExp = converter.clipSub(exp, bias.S +& clzFrac.zext -& 127.S, 255.S, 0.S)
  private val addedExpRaw = exp -& (bias.S +& clzFrac.zext -& 127.S)
  when(addedExpRaw === 0.S) {
    // SubNorm Situation 0
    fp := Cat(sign, 0.U(8.W), 0x7fffff.U(23.W))
  }.elsewhen(addedExpRaw < 0.S) {
    // SubNorm Situation 1
    val sft = clzFrac.zext + addedExpRaw
    val subFrac = Mux(sft > 0.S, fracAbs << sft.asUInt, fracAbs >> (-sft).asUInt)
    if(wF >= 24) {
      fp := Cat(sign, 0.U(8.W), subFrac(wF - 2, wF - 2 - 22))
    } else {
      fp := Cat(sign, 0.U(8.W), subFrac(wF - 2, 0), 0.U((24 - wF).W))
    }
  }.otherwise {
    if(wF >= 24) {
      fp := Cat(sign, addedExp(7, 0), trueFrac(wF - 2, wF - 2 - 22))
    } else {
      fp := Cat(sign, addedExp(7, 0), trueFrac(wF - 2, 0), 0.U((24 - wF).W))
    }
  }

  //  private val normFrac = fracAbs << clzFrac
  //  private val internExp = exp -& (bias.S +& clzFrac.zext -& 127.S)
  //  private val subNormSft = Mux(internExp <= 0.S, 1.S-internExp, 0.S).asUInt
  //  private val trueFrac = normFrac >> subNormSft
  //  private val addedExp = converter.clipSub(internExp, 0.S, 255.S, 0.S)
}

object SignedFPToFPModule extends App {
  ChiselStage.emitSystemVerilog(
    new SignedFPToFPModule(9, 25, 0),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/SignedFPToFPModule"
    )
  )
}


