package utils

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class SrcData[T <: Data](srcW: Int, gen: T) extends Bundle {
  val src = UInt(srcW.W)
  val data = gen
}

class VData[T <: Data](gen: T) extends Bundle {
  val valid = Bool()
  val data  = gen
}

class RoutePackedData[T <: Data](dstW: Int, rDstW: Int, packetPerPort: Int, gen:T) extends Bundle {
  val data = Vec(packetPerPort, new VData(gen))
  val dst  = UInt(dstW.W)
  val reverseDst = Vec(packetPerPort, UInt(rDstW.W))
}

class XnSwitch[T <: Data](log2N: Int, dstW: Int, rDstW: Int, packetPerPort: Int, nextLog2N: Int, gen: T) extends Module {
  val N = 1 << log2N

  val in = IO(Vec(N,
    Flipped(Decoupled(
      new RoutePackedData(dstW, rDstW, packetPerPort, gen)
    ))
  ))

  val out = IO(Decoupled(
    new RoutePackedData(dstW - log2N, rDstW + log2N, packetPerPort * N, gen)
  ))

  // Input FIFO
  val inQ = VecInit(in.map(Queue(_, 2, false, false)))
  val curVld = VecInit(inQ.map(_.valid))
  val curDst = VecInit(inQ.map(_.bits.dst))
  val curPld = VecInit(inQ.map(_.bits.data))
  val curRoute  = VecInit(curDst.map(_(log2N - 1, 0)))
  val nextRoute = if(nextLog2N != 0) {
    VecInit(curDst.map(_(log2N + nextLog2N - 1, log2N)))
  } else {
    VecInit(curDst.map(i => 0.U(1.W)))
  }
  val nextDst = if(dstW != log2N) {
    VecInit(curDst.map(_(dstW - 1, log2N)))
  } else {
    VecInit(curDst.map(i => 0.U))
  }

  // selRoute: RRArbiter
  val routeArb = Module(new RRArbiter(nextDst(0).cloneType, N))
  routeArb.io.in <> VecInit.tabulate(N)(i => {
    val r = Wire(Decoupled(nextDst(0).cloneType))
    r.valid := curVld(i)
    r.bits := nextDst(i)
    r
  })

  out.bits.dst := routeArb.io.out.bits
  routeArb.io.out.ready := out.ready

  val selRoute = if(nextLog2N != 0) {
    out.bits.dst(nextLog2N - 1, 0)
  } else {
    0.U(1.W)
  }
  val routeTableAllow = VecInit.tabulate(N)(lIdx => {
    nextRoute(lIdx) === selRoute
  })

  val globalValidMap = routeTableAllow.asUInt & curVld.asUInt

  val fireVec = Wire(Vec(N, Vec(N, Bool())))
  val fireVecR = VecInit(fireVec.map(_.asUInt)).reduceTree(_ | _)
  for(port <- 0 until N) {
    inQ(port).ready := fireVecR(port) & out.ready
    val routeMask = VecInit(curRoute.map(_ === port.U)).asUInt
    val activeMask = globalValidMap & routeMask
//    val selData = PriorityMux(activeMask, curPld)
//    fireVec(port) := PriorityEncoderOH(activeMask)
    val arb = Module(new RRArbiter(curPld(0).cloneType, N))
    arb.io.in <> VecInit.tabulate(N)(i => {
      val r = Wire(Decoupled(curPld(0).cloneType))
      r.valid := activeMask(i)
      r.bits := curPld(i)
      fireVec(port)(i) := (arb.io.chosen === i.U) & arb.io.out.valid
      r
    })
    val selData = arb.io.out.bits
    arb.io.out.ready := out.ready
    for(packet <- 0 until packetPerPort) {
      out.bits.data(packet + port * packetPerPort) := selData(packet)
      out.bits.data(packet + port * packetPerPort).valid := selData(packet).valid & activeMask.orR
      out.bits.reverseDst(packet + port * packetPerPort) := Cat(curRoute(port), inQ(port).bits.reverseDst(packet))
    }
  }
  out.valid := curVld.asUInt.orR
}

// Simplified version of Crossbar, using not-fully-connected topology.
// Multi-level Grouped Packet(Flit) Exchange Network
class GroupXBar[T <: Data](log2N: Int,
                           layerInfo: Seq[Int], // switch (log2Src, log2Dst)
                           routerPolicy: (T) => UInt, gen: T) extends Module {
  val N = 1 << log2N
  val in = IO(Vec(N, Flipped(Decoupled(gen))))

  require(layerInfo.sum == log2N)

  // Pack Input
  var tFlow  = Seq.empty[Vec[DecoupledIO[RoutePackedData[SrcData[T]]]]]
  tFlow = VecInit.tabulate(N)(i => {
    val t = Wire(Decoupled(new RoutePackedData(log2N, 1, 1, new SrcData(log2N, gen))))
    t.bits.data(0).data.data  := in(i).bits
    t.bits.data(0).data.src   := i.U
    t.bits.data(0).valid      := in(i).valid
    t.bits.reverseDst(0)            := 0.U
    t.bits.dst                := routerPolicy(in(i).bits)
    t.valid := in(i).valid
    in(i).ready := t.ready
    t
  }) +: tFlow

  // Layers
  var curGroup = N
  var packetPerPort = 1
  var curDstW = log2N
  var curRDstW = 1
  for(layer <- layerInfo.indices) {
    val layerLog2N = layerInfo(layer)
    val layerN = 1 << layerLog2N
    val switchCount = curGroup / layerN

    val nextIO = Wire(Vec(switchCount, Decoupled(
      new RoutePackedData(curDstW - layerLog2N, curRDstW + layerLog2N, packetPerPort * layerN, new SrcData(log2N, gen))
    )))
    for(switch <- 0 until switchCount) {
      val partitialIn = VecInit.tabulate(layerN)(i => tFlow.head(switch * layerN + i))
      val s = Module(new XnSwitch(layerLog2N, curDstW, curRDstW, packetPerPort,
        if(layer == layerInfo.length - 1) 0 else layerInfo(layer + 1), new SrcData(log2N, gen)))
      s.in <> partitialIn
      s.out <> nextIO(switch)
    }

    tFlow = nextIO +: tFlow
    curGroup = curGroup / layerN
    packetPerPort = packetPerPort * layerN
    curDstW = curDstW - layerLog2N
    curRDstW = curRDstW + layerLog2N
  }

  // Unpack Output
  val out = IO(Decoupled(Vec(N, gen)))
  val outVld = IO(Output(Vec(N, Bool())))
  val outSrc = IO(Output(Vec(N, UInt(log2N.W)))) // Used For Assertion Check
  val outReverseRoute = IO(Output(Vec(N, UInt(log2N.W))))
  val outRVld = IO(Output(Vec(N, Bool())))
  val outRSrc = IO(Output(Vec(N, UInt(log2N.W))))
  val outR    = IO(Output(Vec(N, gen)))

  for(port <- 0 until N) {
    out.bits(port) := tFlow.head(0).bits.data(port).data.data
    outSrc(port)   := tFlow.head(0).bits.data(port).data.src
    outVld(port)   := tFlow.head(0).bits.data(port).valid
    outReverseRoute(port) := tFlow.head(0).bits.reverseDst(port)(log2N, 1)
    outRVld(port)  := outVld(outReverseRoute(port)) & (outRSrc(port) === port.U)
    outRSrc(port)  := outSrc(outReverseRoute(port))
    outR(port)     := tFlow.head(0).bits.data(outReverseRoute(port)).data.data
  }
  tFlow.head(0).ready := out.ready
  out.valid := tFlow.head(0).valid

}

object GroupXBar extends App {
  ChiselStage.emitSystemVerilog(
    new GroupXBar(5, Seq(2, 3), (i: UInt) => i(4, 0), UInt(32.W)),
    firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-preserve-aggregate=vec",
    "--split-verilog",
    "-o=generated/GroupXBar")
  )
}
