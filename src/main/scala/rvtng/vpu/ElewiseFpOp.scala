package rvtng.vpu

import chisel3._
import chisel3.util._

import Util._


class ALUFpUnit[T <: Data](dataWidth: Int, val datatype : T) (implicit ev: Arithmetic[T])extends Module {
  import  ev._
  val io = IO(new Bundle {
    val a = Input(datatype)
    val b = Input(datatype)
    val opSel = Input(UInt(6.W)) // 0: add, 1:sub, 2:mul
    val valid = Input(Bool())

    val result = Output(datatype)
    val output_valid = Output(Bool())
  })
  
  val addResult = io.a + io.b
  val subResult = io.a - io.b
  val mulResult = io.a * io.b
  
  val outputValidAddSub = ShiftRegister(io.valid, 4)
  val outputValidMul = ShiftRegister(io.valid, 2)

  val opSelAdd :: opSelSub :: opSelMul :: Nil = Enum(3)

  io.result := MuxLookup(io.opSel, 0.U.asTypeOf(datatype))(Seq(
    opSelAdd -> addResult,
    opSelSub -> subResult,
    opSelMul -> mulResult,
  ))
  io.output_valid := MuxLookup(io.opSel, false.B)(Seq(
    opSelAdd -> outputValidAddSub,
    opSelSub -> outputValidAddSub,
    opSelMul -> outputValidMul,
  ))

}

class ElewiseFpOp[T <: Data](dataWidth: Int, val datatype : T, ComputeUnitnums: Int) (implicit ev: Arithmetic[T])extends Module {

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new ElewiseOpIn(dataWidth, datatype, ComputeUnitnums)))
    val output = Decoupled(new ElewiseOpOut(dataWidth, datatype, ComputeUnitnums))
  })

  val broadcaster = Module(new ColumnBroadcaster(dataWidth, datatype, ComputeUnitnums))
  broadcaster.io.data_row := io.input.bits.b
  broadcaster.io.broadcastDim := io.input.bits.broadcastDim

  val computeUnits = Seq.fill(ComputeUnitnums)(Module(new ALUFpUnit(dataWidth, datatype)))
  for (i <- 0 until ComputeUnitnums) {
    computeUnits(i).io.a := io.input.bits.a(i).asTypeOf(datatype)
    computeUnits(i).io.b := broadcaster.io.processed_data(i).asTypeOf(datatype)
    computeUnits(i).io.opSel := io.input.bits.opSel
    computeUnits(i).io.valid := io.input.valid
  } 

  val resultNow = Wire(Vec(ComputeUnitnums, datatype))
  for (i <- 0 until ComputeUnitnums) { resultNow(i) := computeUnits(i).io.result }
  val delay_valid = computeUnits.map(_.io.output_valid).reduce(_||_)

  io.input.ready := true.B
  io.output.valid := delay_valid
  io.output.bits.result := resultNow 

  
}


