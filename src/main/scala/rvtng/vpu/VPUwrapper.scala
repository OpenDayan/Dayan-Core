package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._

import chisel3.internal.HasId
import scala.collection.mutable.ArrayBuffer

/* wrapper for each Bank IO of scratchpad */

class VPUwrapper[T <: Data](params: VpuParams)(implicit ev: Arithmetic[T]) extends Module {
  import params._
  import ev._
  
  val io = IO(new Bundle {
    // NRAM接口

    val srams = new Bundle {
      val read = new ScratchpadReadIO(sp_bank_entries, row_width)
      val write = new ScratchpadWriteIO(sp_bank_entries, row_width, (row_width / (aligned_to * 8)) max 1)
    }
    
    // 控制信号
    val start      = Input(Bool())
    val cmd        = Input(new GemminiCmd(reservation_station_entries))
    
    // 状态信号
    val busy       = Output(Bool())
    val done       = Output(Bool())
  })



  val ElementwiseUnit = Module(new ElewiseUnit(params))
  

  ElementwiseUnit.io.start := io.start
  ElementwiseUnit.io.cmd := io.cmd

  io.busy := ElementwiseUnit.io.busy
  io.done := ElementwiseUnit.io.done

  io.srams.read <> ElementwiseUnit.io.uram_read_1

  ElementwiseUnit.io.uram_read_2 <> DontCare

  io.srams.write <> ElementwiseUnit.io.uram_write
  

}

