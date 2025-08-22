package rf

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import _root_.circt.stage.ChiselStage

// 定义通用的IO结构
class RF1RWReq[T <: Data](sourceL:Int, depth:Int, gen: T) extends Bundle {
  val valid  = Bool
  val source = UInt(sourceL.W)
  val write  = Bool
  val  addr  = UInt(log2Ceil(depth).W)
  val wdata  = gen
}
class RF1RWResp[T <: Data](sourceL:Int, depth:Int, gen: T) extends Bundle {
  val source = UInt(sourceL.W)
  val rdata  = gen
}

trait RF1RWInterface[T <: Data](sourceL:Int, depth:Int, gen: T) extends Module {
  // 定义抽象的IO端口，具体模块需要实现这个IO
  def req = Input(new RF1RWReq(sourceL, depth, gen))
  def resp = Input(new RF1RWReq(sourceL, depth, gen))
}

// Stage: Maxium Registers That need to be read
// Bank: Storage Bank
// Depth: Per Bank Depth
class RFPipe[T <: Data, S <: Module](stage: Int, bank: Int, depth: Int, gen: T, storageUnit: () => S) extends Module {
  val ptrL = log2Ceil(bank) + log2Ceil(depth)
  val readReq = IO(Vec(stage, Input(UInt(ptrL.W))))       // Read Request
  val write = IO(Tuple3(Bool, Input(UInt(ptrL.W)), Input(gen))) // Write Request
  val readResp = IO(Vec(stage, Output(gen)))

  // Storage Define
  val storages = Seq.fill(bank)(Module(storageUnit()))

  // Request Reduce
  class reqT extends Bundle {
    val valid  = Bool
    val source = UInt(stage.W)
    val write  = Bool
    val  addr  = UInt(log2Ceil(depth).W)
    val wdata  = gen
  }

  val raw_req = Wire(Vec(bank, Vec(stage + 1, new reqT)))
  val req = raw_req.map(_.reduceTree((l,r) => {
    chisel3.assert(!(l.valid & r.valid), "RFPipe Bank Conflict!")
    Mux(l.valid, l, r)
  }))
  val raw_resp = Wire(Vec(stage, Vec(bank, Tuple2(Bool, gen))))
  val resp = raw_resp.map(_.reduceTree((l,r) => Mux(l._1, l, r)))

  for(b <- 0 until bank) {
    storages(b).valid = req(b).source
    storages(b).tag = req(b).source
    storages(b).write = req(b).write & req(b).valid
    storages(b).addr = req(b).addr
    storages(b).wdata = req(b).wdata
  }
}
