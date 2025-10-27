package RVTNG.vec

import chisel3._
import chisel3.util._
// _root_ disambiguates from package chisel3.util.circt if user imports chisel3.util._

class VecTCtrl extends Module {
  val fetch  = IO(Decoupled(UInt(VecConfiguration._VEC_IQ_ENTRY_LOG2.W)))
  val inst_i = IO(Decoupled(Flipped(new VecInstT)))

  // From Scheduler
  val req = IO(Decoupled(Flipped(new VecTile)))


  // Global Resource Scheduling
  val inst_o =IO(Decoupled(Flipped(new VecInstExecT)))
}
