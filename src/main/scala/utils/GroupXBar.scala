package utils

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class TaggedData[T <: Data](log2InN: Int, log2OutN: Int, gen: T) extends Bundle {
  val valid = Bool()
  val src   = UInt(log2InN.W)
  val data  = gen
}

class GroupXnSwitch[T <: Data](log2InN: Int, log2OutN: Int, nextLog2OutN: Int, routeLength: Int, genT: T) extends Module {
  val inN  = 1 << log2InN
  val outN = 1 << log2OutN

  val inValid = IO( Input(Vec(inN, Bool())))
  val inRoute = IO( Input(Vec(inN, UInt(routeLength.W))))
  val in      = IO( Input(Vec(inN, genT)))
  val inReady = IO(Output(Bool()))

  val outValid  = IO(Output(Vec(outN, Bool())))
  val outRoute  = IO(Output(UInt((routeLength - log2OutN).W)))
  val outGValid = IO(Output(Bool()))
  val outReady  = IO( Input(Bool()))
  val out = IO(Vec(outN, genT))

  // Input latch
  val latchPayload  = RegEnable(in, inReady)
  val latchValid    = RegInit(0.U(inN.W))
  val latchRoute    = if(routeLength > log2OutN) {
    RegEnable(VecInit(inRoute.map(_(routeLength - 1, log2OutN))), inReady)
  } else {
    VecInit(inRoute.map(i => 0.U))
  }
  val nextRouteTable = if(nextLog2OutN > 0) {
    RegEnable(VecInit(inRoute.map(_(log2OutN + nextLog2OutN - 1, log2OutN))), inReady)
  } else {
    VecInit(inRoute.map(i => 0.U))
  }
  val curRouteTable  = RegEnable(VecInit(inRoute.map(_(log2OutN - 1, 0))),            inReady)
  val updateIssue = Wire(Vec(inN, UInt(inN.W)))

  val routeTableCmpMap = VecInit.tabulate(inN)(lIdx => {
    VecInit.tabulate(inN)(rIdx => {
      nextRouteTable(lIdx) === nextRouteTable(rIdx)
    })
  })
  val routeTableAllow = VecInit.tabulate(inN)(cPort => {
    VecInit.tabulate(cPort + 1)(tPort => {
      routeTableCmpMap(cPort)(tPort) | !latchValid(tPort)
    }).reduceTree((l, r) => l & r)
  })

  updateIssue := 0.U.asTypeOf(updateIssue)
  when(inReady) {
    latchValid := inValid.asUInt
  }.otherwise {
    latchValid := latchValid & (~updateIssue.reduceTree(_ | _)).asUInt
  }

  // CheckOne Reduce Tree
  val checkOne = VecInit.tabulate(inN)(i => {
    Cat(0.U(1.W), latchValid(i)) // MoreThanOne, hasOne
  }).reduceTree((l, r) => {
    Cat((l(0) & r(0)) | (l(1) | r(1)), l(0) | r(0))
  })
  val leftOne = checkOne(0) & ~checkOne(1)
  val leftZero = ~checkOne(0)

  inReady := (leftOne & outReady) | leftZero

  val globalValidMap = routeTableAllow.asUInt & latchValid
  outRoute := PriorityMux(globalValidMap, latchRoute)

  // Output Mux
  for(port <- 0 until outN) {
    val routeMask = VecInit.tabulate(inN)(curRouteTable(_) === port.U).asUInt
    val activeMask = globalValidMap & routeMask
    out(port) := PriorityMux(activeMask, latchPayload)
    outValid(port) := activeMask.orR
    when(outReady) {
      updateIssue(port) := PriorityEncoderOH(activeMask)
    }
  }
  outGValid := latchValid.orR
}

// Simplified version of Crossbar, using not-fully-connected topology.
// Multi-level Grouped Packet(Flit) Exchange Network
class GroupXBar[T <: Data](log2InN: Int = 2, log2OutN: Int = 2,
                           layerInfo: Seq[(Int, Int)], // switch (log2Src, log2Dst)
                           routerPolicy: (T) => UInt, gen: T) extends Module {
  val inN  = 1 << log2InN
  val outN = 1 << log2OutN

  // TODO: Other Situation?
  require(inN == outN)

  val in = IO(Vec(inN, Flipped(Decoupled(gen))))
  val out = IO(Vec(inN, Decoupled(gen)))

  var curSwitchCount = inN
  var layerPacket = Seq.empty[Vec[TaggedData[T]]]
  var layerGValid = Seq.empty[Vec[Bool]] // Per Group
  var layerRoute  = Seq.empty[Vec[UInt]]
  var layerReady  = Seq.empty[Vec[Bool]]

  // Input Packing
  layerPacket = VecInit.tabulate(inN)(i => {
    val p = Wire(new TaggedData(log2InN, log2OutN, gen))
    p.valid := in(i).valid
    p.src   := i.U
    p.data  := in(i).bits
    p
  }) +: layerPacket
  layerGValid = VecInit.tabulate(inN)(i => {
    in(i).valid
  }) +: layerGValid
  layerRoute = VecInit.tabulate(inN)(i => {
    routerPolicy(in(i).bits)
  }) +: layerRoute
  val inReady = Wire(Vec(inN, Bool()))
  layerReady = inReady +: layerReady
  for(i <- in.indices) { in(i).ready := inReady(i) }

  var routeBitsNow = 0
  var packetPerPort  = 1
  var routeLength = log2OutN
  for(layer <- layerInfo.indices) {
    val inPortCount  = 1 << layerInfo(layer)._1
    val outPortCount = 1 << layerInfo(layer)._2
    curSwitchCount = curSwitchCount / inPortCount

    // TODO: Other Situation?
    require(inPortCount == outPortCount)
    require(curSwitchCount * inPortCount * packetPerPort == inN)

    // Wires Defination
    val outReady  = Wire(Vec(curSwitchCount, Bool()))
    val outRoute  = Wire(Vec(curSwitchCount, UInt(routeLength.W)))
    val outGValid = Wire(Vec(curSwitchCount, Bool()))
    val outPacket = Wire(Vec(curSwitchCount, Vec(inPortCount, Vec(packetPerPort,
      new TaggedData(log2InN, log2OutN, gen)))))

    // For Each Switch
    for(switch <- 0 until curSwitchCount) {
      // Gather Packet
      val gatheredPacket = VecInit.tabulate(inPortCount)(port => {
        VecInit.tabulate(packetPerPort)(packet => {
          val t = layerPacket.head(packet + (port + switch * inPortCount) * packetPerPort)
          t
        })
      })
      val gatheredValid = VecInit.tabulate(inPortCount)(port => {
        val t = layerGValid.head(switch * inPortCount + port)
        t
      })
      val gatheredRoute = VecInit.tabulate(inPortCount)(port => {
        val t = layerRoute.head(switch * inPortCount + port)
        t
      })

      val s = Module(new GroupXnSwitch(layerInfo(layer)._1, layerInfo(layer)._2,
        if(layer == layerInfo.length-1) 0 else layerInfo(layer + 1)._2, routeLength,
        Vec(packetPerPort, new TaggedData(log2InN, log2OutN, gen)))
      )

      s.in      := gatheredPacket
      s.inValid := gatheredValid
      s.inRoute := gatheredRoute
      for(port <- 0 until inPortCount) {
        layerReady.head(switch * inPortCount + port) := s.inReady
      }

      outRoute(switch)  := s.outRoute
      outGValid(switch) := s.outGValid
      outPacket(switch) := s.out
      s.outReady        := outReady(switch)
      for(port <- 0 until outPortCount) {
        for(packet <- 0 until packetPerPort) {
          outPacket(switch)(port)(packet).valid := s.out(port)(packet).valid & s.outValid(port)
        }
      }
    }

    layerReady  = outReady  +: layerReady
    layerRoute  = outRoute  +: layerRoute
    layerGValid = outGValid +: layerGValid
    layerPacket = outPacket.asTypeOf(Vec(curSwitchCount * inPortCount * packetPerPort,
      new TaggedData(log2InN, log2OutN, gen))) +: layerPacket

    routeLength    = routeLength - layerInfo(layer)._2
    packetPerPort  = packetPerPort * outPortCount
  }

  // Output Unpacking
  for(port <- 0 until outN) {
    out(port).bits  := layerPacket.head(port).data
    out(port).valid := layerPacket.head(port).valid
    assert(!out(port).valid | out(port).ready)
  }
  layerReady.head(0) := 1.B // Assert no stalling

}

object GroupXBar extends App {
  ChiselStage.emitSystemVerilog(
    new GroupXBar(5, 5,
      Seq((2,2), (3,3)),
      (in: UInt) => in(4, 0),
      UInt(32.W)),
    firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-preserve-aggregate=vec",
    "--split-verilog",
    "-o=generated/GroupXBar")
  )
}
