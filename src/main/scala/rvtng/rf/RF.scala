package rvtng.rf

import chisel3._
import chisel3.util._

class RFWReq[T <: Data](ptrL:Int, gen: T) extends Bundle {
  val  addr  = UInt(ptrL.W)
  val wdata  = gen
}

class RF1RWReq[T <: Data](sourceL:Int, depth:Int, gen: T) extends Bundle {
  val valid  = Bool()
  val write  = Bool()
  val source = UInt(sourceL.W)
  val  addr  = UInt(log2Ceil(depth).W)
  val wdata  = gen
}

class RF1RWResp[T <: Data](sourceL:Int, depth:Int, gen: T) extends Bundle {
  val source = UInt(sourceL.W)
  val rdata  = gen
}

trait RF1RWIntf[T <: Data] {
  val req: RF1RWReq[T]
  val resp: RF1RWResp[T]
}

class RF1RW_FFBased[T <: Data](sourceL:Int, depth:Int, gen: T) extends Module with RF1RWIntf[T]{
  val req = IO(Input(new RF1RWReq(sourceL, depth, gen)))
  val resp = IO(Output(new RF1RWResp(sourceL, depth, gen)))
  val mem = Reg(Vec(depth, gen))
  when(req.valid & req.write) {
    mem(req.addr) := req.wdata
  }
  val readQ = RegNext(mem(req.addr))
  val sourceQ = RegNext(req.source)
  resp.rdata := readQ
  resp.source := sourceQ
}

