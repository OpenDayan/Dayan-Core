package rvtng.vec

import chisel3._
import chisel3.util._

class VecFUDispatch(params: VecParams) extends Module {
  val in = IO(Flipped(Decoupled(new VecInstRData(params))))

  val outInt = IO(Decoupled(new VecInstRData(params)))
  val outLsu = IO(Decoupled(new VecInstRData(params)))

  // TODO: Add Other FU here.
  outInt.valid := in.valid & in.bits.inst.fu === VecFuEnum.Int
  outInt.bits  := in.bits
  outLsu.valid := in.valid & in.bits.inst.fu === VecFuEnum.Lsu
  outLsu.bits  := in.bits

  // Assertion 检查，流式接口不可阻塞。
  in.ready := 1.B
  chisel3.assert(!(outInt.valid & !outInt.ready))
  chisel3.assert(!(outLsu.valid & !outLsu.ready))

}
