package rvtng.utils

import chisel3._
import chisel3.util._

// This Module is Designed for Out-Of-Order Memory Transaction ID Generation.
// Inorder Allocate / Release of ID
// Allocate: 0.1.2.3.4.5
// Release : 0.1.2.3.4.5
class ReorderIdTableInorder(idWidth: Int = 5) extends Module{
  val idOut = IO(Flipped(Decoupled(UInt(idWidth.W))))
  val idIn  = IO(Decoupled(UInt(idWidth.W)))
  val idQ = Module(new Queue(UInt(idWidth.W), 2, false, false))

  idOut <> idQ.io.deq

  val ptrW = idWidth + 1
  val genPtr = RegInit(0.U(ptrW.W))
  val releasePtr = RegInit(0.U(ptrW.W))

  val lowEq = genPtr(idWidth - 1, 0) === releasePtr(idWidth - 1, 0)
  val hiEq = genPtr(idWidth) === releasePtr(idWidth)

  val full = !hiEq & lowEq
  val empty = hiEq & lowEq

  idQ.io.enq.bits  := genPtr(idWidth - 1, 0)
  idQ.io.enq.valid := !full
  when(idQ.io.enq.fire) {
    genPtr := genPtr + 1.U
  }

  idIn.ready := 1.B
  when(idIn.fire) {
    releasePtr := releasePtr + 1.U
  }

  chisel3.assert(!(empty & idIn.fire), "Release when no allocated ID.")
  chisel3.assert(!(idIn.fire & idIn.bits =/= releasePtr), "Release are not inordered!")
}
