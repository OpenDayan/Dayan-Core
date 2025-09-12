package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import utils.RF1R1WRamBase

// Memory Wrapper For DPArray Buffer
// Read  as entries, Vec(mn, Vec(k, UInt(8.W)))
// Write as entries, Vec(busWidth, UInt(8.W))
class DPArrayBufferAB(busWidth: Int, entries: Int, mn: Int, k: Int) extends Module {
  val ptrW = log2Ceil(entries)
  val maxBeat = (mn * k) / busWidth
  val beatW = log2Ceil(maxBeat)
  require(maxBeat == 0 | (busWidth * maxBeat == mn * k))
  // If busWidth > mn * k, means this is a Scale buffer;
  val we    = IO(Input(Bool()))
  val wAddr = IO(Input(UInt(ptrW.W)))
  val wBeat = IO(Input(UInt(beatW.W)))
  val wData = IO(Input(Vec(busWidth, UInt(8.W))))

  // RE is not present
  val rAddr = IO(Input(UInt(ptrW.W)))
  val rData = IO(Output(Vec(mn, Vec(k, UInt(8.W)))))

  // Real rData
  val realRData = Wire(UInt((mn * k * 8).W))
  rData := realRData.asTypeOf(Vec(mn, Vec(k, UInt(8.W))))

  // Backing Storage Buffer
  if(maxBeat <= 1) {
    val backingStorage = Module(new RF1R1WRamBase(ptrW, mn * k * 8))
    backingStorage.io.clk := clock
    backingStorage.io.reset := reset
    backingStorage.io.raddr := rAddr
    realRData := backingStorage.io.rdata
    backingStorage.io.we := we
    backingStorage.io.waddr := wAddr
    backingStorage.io.wdata := wData.asUInt(mn * k * 8 - 1, 0)
  } else {
    val tiledRData = Wire(Vec(maxBeat, UInt((busWidth * 8).W)))
    for(i <- 0 until maxBeat) {
      val backingStorage = Module(new RF1R1WRamBase(ptrW, busWidth * 8))
      backingStorage.io.clk := clock
      backingStorage.io.reset := reset
      backingStorage.io.raddr := rAddr
      tiledRData(i) := backingStorage.io.rdata
      backingStorage.io.we := we & (i.U === wBeat)
      backingStorage.io.waddr := wAddr
      backingStorage.io.wdata := wData.asUInt
    }
    realRData := tiledRData.asUInt
  }

}

object DPArrayBufferAB extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayBufferAB(256, 32, 16, 32),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayBufferAB"
    )
  )
}
