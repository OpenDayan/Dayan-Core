package rvtng

import chisel3._

package object utils {
  // Input: x, shamt
  // Output: Shifted Value, sticky bit
  def genPipe[T <: Data](x: T, stage: Int): Vec[T] = {
    val pipe = Wire(Vec(stage + 1, UInt(x.getWidth.W)))
    pipe(0) := x.asUInt
    (1 to stage).foreach(index => pipe(index) := RegNext(pipe(index - 1)))
    VecInit.tabulate(stage + 1)(pipe(_).asTypeOf(x))
  }
}