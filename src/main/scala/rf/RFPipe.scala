package rf

import chisel3._
import chisel3.util._
import chisel3.util.SparseVec.Lookup._
import chisel3.experimental.BundleLiterals._
import _root_.circt.stage.ChiselStage

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

//trait RF1RWInterface[T <: Data](sourceL:Int, depth:Int, gen: T) extends Module {
//  def req = Input(new RF1RWReq(sourceL, depth, gen))
//  def resp = Output(new RF1RWResp(sourceL, depth, gen))
//}

trait RF1RWIntf[T <: Data] {
  val req: RF1RWReq[T]
  val resp: RF1RWResp[T]
}

class RF1RW[T <: Data](sourceL:Int, depth:Int, gen: T) extends Module with RF1RWIntf[T]{
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

// Stage: Maxium Registers That need to be read
// Bank: Storage Bank
// Depth: Per Bank Depth
class RFPipe[T <: UInt, S <:  Module with RF1RWIntf[T]]
(stage: Int, bank: Int, depth: Int, gen: T, storageUnit: (Int, Int, T) => S) extends Module {
  val ptrL = log2Ceil(bank) + log2Ceil(depth)
  val readReq = IO(Input(Vec(stage,UInt(ptrL.W))))       // Read Request
  val write = IO(Input(new RFWReq(ptrL, gen))) // Write Request
  val readResp = IO(Output(Vec(stage, gen)))

  // Storage Define
  val storages = Seq.fill(bank)(Module(storageUnit(stage, depth, gen)))

  val raw_req = Wire(Vec(bank, Vec(stage + 1, new RF1RWReq(stage, depth, gen))))
  val req = raw_req.map(_.reduceTree((l,r) => {
    chisel3.assert(!((l.valid & r.valid) | (l.write & r.write) | (l.wdata & r.wdata)), "RFPipe Bank Conflict!")
    val t = Wire(new RF1RWReq(stage, depth, gen))
    t := Mux(l.valid, l, r)
    t.valid := l.valid | r.valid
    t.write := l.write | r.write
    t.wdata := l.wdata | r.wdata
    t
  }))
  val resp = Wire(Vec(bank, new RF1RWResp(stage, depth, gen)))

  for(b <- 0 until bank) {
    storages(b).req := req(b)
    resp(b) := storages(b).resp
  }

  // Request Pipeline
  val req_pipe = Wire(Vec(stage, Vec(stage, UInt(ptrL.W))))
  req_pipe(0) := readReq
  for(s <- 1 until stage) {
    val prevReq = RegNext(req_pipe(s - 1))
    req_pipe(s) := prevReq
  }

  // Issue Request
  for(s <- 0 until stage) {
    val req = req_pipe(s)(s)
    val trueAddr = req(ptrL-1, log2Ceil(bank))
    val trueBank = req(log2Ceil(bank)-1, 0)
    raw_req.zipWithIndex.foreach {case(req, index) => {
      req(s).valid := trueBank =/= index.asUInt
      req(s).write := 0.B
      req(s).source := OneHot.encoding(s, stage + 1)
      req(s).addr := trueAddr
      req(s).wdata := 0.U
    }}
    readResp(s) := resp.map((resp) => Mux(resp.source(s), resp.rdata, 0.U)).reduce(_|_)
  }
  raw_req.zipWithIndex.foreach {case(req, index) => {
    req(stage).valid := write.addr(ptrL-1, log2Ceil(bank)) =/= index.asUInt
    req(stage).write := 1.B
    req(stage).source := OneHot.encoding(stage, stage + 1)
    req(stage).addr := write.addr(ptrL-1, log2Ceil(bank))
    req(stage).wdata := write.wdata
  }}
}

object RFPipe extends App {
  ChiselStage.emitSystemVerilogFile(
    new RFPipe(
      4, 4, 32, UInt(32.W),
      (stage, depth, gen: UInt) => new RF1RW(stage, depth, gen)
    ),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}
