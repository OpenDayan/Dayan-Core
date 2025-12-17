package rvtng.vpu

import chisel3._
import chisel3.util._

import Util._


import convertutils._
import convertutils.ConvertType._
import convertutils.ConvertMode._


object ConvertUnitModule {
  def apply[T <: Data](in: T, mode: UInt, out: T): T = {
    (in, out) match {
    // 检查 a 是 FP8E4M3 (exp=4, sig=4)，且 c 是 FP32 (exp=8, sig=24)
    case (inFloat: Float, outFloat: Float) 
      if (inFloat.expWidth == 8 && inFloat.sigWidth == 24 && 
         outFloat.expWidth == 4 && outFloat.sigWidth == 4) => {      
          val fp32_to_e4m3 = Module(new FP32ToE4M3)
          fp32_to_e4m3.io.in := inFloat.asUInt
          fp32_to_e4m3.io.mode := mode
          fp32_to_e4m3.io.out.asTypeOf(out)
      } 
    case (inFloat: Float, outFloat: Float) 
     if (inFloat.expWidth == 8 && inFloat.sigWidth == 24 && 
         outFloat.expWidth == 8 && outFloat.sigWidth == 8) => {      
          val fp32_to_bf16 = Module(new FP32ToBF16)
          fp32_to_bf16.io.in := inFloat.asUInt
          fp32_to_bf16.io.mode := mode
          fp32_to_bf16.io.out.asTypeOf(out)
      }

    case _ => in
    }
  }
}


class ConvertUnit[T <: Data](dataWidth: Int, val datatype : T, convertDataWidth: Int, val convertDataType: T) (implicit ev: Arithmetic[T])extends Module {
  import  ev._
  import Arithmetic.FloatArithmetic._
  val io = IO(new Bundle {
    val a            = Input(datatype)
    val mode         = Input(UInt(2.W))
    val valid        = Input(Bool())
    val result       = Output(convertDataType)
    val output_valid = Output(Bool())
  })


  val result = WireInit(0.U.asTypeOf(convertDataType))
  val outputValid = WireInit(false.B)

  when(io.valid) {
    result := ConvertUnitModule(io.a, io.mode, io.result)
    outputValid := true.B
  }

  io.result       := result
  io.output_valid := outputValid

}


class ConvertIn[T <: Data](dataWidth: Int, datatype : T, ComputeUnitnums: Int) extends Bundle{
  val a = Vec(ComputeUnitnums, datatype)
  val mode = UInt(2.W)
  // val inType = UInt(7.W)
  // val outType = UInt(7.W)
}

class ConvertOut[T <: Data](dataWidth: Int, datatype : T, ComputeUnitnums: Int) extends Bundle{    
  val result = Vec(ComputeUnitnums, datatype)
}

class ElewiseConvertOp[T <: Data](dataWidth: Int, val datatype : T, convertDataWidth: Int, 
                          val convertDataType : T, ComputeUnitnums: Int) (implicit ev: Arithmetic[T])extends Module {
  import  ev._
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new ConvertIn(dataWidth, datatype, ComputeUnitnums)))
    val output = Decoupled(new ConvertOut(convertDataWidth, convertDataType, ComputeUnitnums))
  })

  val result = Wire(Vec(ComputeUnitnums, convertDataType))
  io.input.ready := true.B

  val computeUnits = Seq.fill(ComputeUnitnums)(Module(new ConvertUnit(dataWidth, datatype, convertDataWidth, convertDataType)))
  for (i <- 0 until ComputeUnitnums) {
    computeUnits(i).io.a := io.input.bits.a(i).asTypeOf(datatype)
    computeUnits(i).io.mode := io.input.bits.mode
    // computeUnits(i).io.inType := io.input.bits.inType
    // computeUnits(i).io.outType := io.input.bits.outType
    computeUnits(i).io.valid := io.input.valid
    result(i) := computeUnits(i).io.result
  }
  
  io.output.bits.result := result
  io.output.valid :=  computeUnits.map(_.io.output_valid).reduce(_||_)

}