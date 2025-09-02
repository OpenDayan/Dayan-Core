package utils

import chisel3._
import chisel3.util._

package object pipe {
  // Input: x, shamt
  // Output: Shifted Value, sticky bit
  def genPipe[T <: Data](x: T, stage: Int): Vec[T] = {
    val pipe = Wire(Vec(stage + 1, x))
    pipe(0) := x
    (1 to stage).foreach(index => pipe(index) := RegNext(pipe(index - 1)))
    pipe
  }
}