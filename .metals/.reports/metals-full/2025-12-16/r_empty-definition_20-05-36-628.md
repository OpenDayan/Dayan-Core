error id: file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUwrapper.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUwrapper.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/row_width.
	 -chisel3/row_width#
	 -chisel3/row_width().
	 -chisel3/util/row_width.
	 -chisel3/util/row_width#
	 -chisel3/util/row_width().
	 -GemminiISA.row_width.
	 -GemminiISA.row_width#
	 -GemminiISA.row_width().
	 -Util.row_width.
	 -Util.row_width#
	 -Util.row_width().
	 -params/row_width.
	 -params/row_width#
	 -params/row_width().
	 -ev/row_width.
	 -ev/row_width#
	 -ev/row_width().
	 -row_width.
	 -row_width#
	 -row_width().
	 -scala/Predef.row_width.
	 -scala/Predef.row_width#
	 -scala/Predef.row_width().
offset: 1229
uri: file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUwrapper.scala
text:
```scala
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

  class WriteReq extends Bundle{
    val writeio = new ScratchpadWriteIO(sp_bank_entries, row_width, (row_width@@ / (aligned_to * 8)) max 1)
    val last = Bool()
  }

  io.srams.write <> ElementwiseUnit.io.uram_write
  

}


```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.