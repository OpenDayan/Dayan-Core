package convertutils

import chisel3._
import chisel3.util._


// TODO: rounding mode 
class DummyConvert extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

}