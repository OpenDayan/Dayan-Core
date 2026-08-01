/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package rvtng.utils

import chisel3._
import chisel3.util._

// 1-Cycle Fixed Latency
class RF1R1WRamBase(addressWidth: Int, dataWidth: Int) extends
  BlackBox(Map("addressWidth" -> addressWidth, "dataWidth" -> dataWidth)) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Reset())

    val re = Input(UInt(1.W))
    val raddr = Input(UInt(addressWidth.W))
    val rdata = Output(UInt(dataWidth.W))

    val we = Input(UInt(1.W))
    val waddr = Input(UInt(addressWidth.W))
    val wdata = Input(UInt(dataWidth.W))
  })
  addResource("/verilog/RF1R1WRamBase.sv")
}

// 1-Cycle Fixed Latency
class RF1R1WRam(addressWidth: Int, dataWidth: Int) extends Module {
  val raddr = IO(Input(UInt(addressWidth.W)))
  val rdata = IO(Output(UInt(dataWidth.W)))
  val we = IO(Input(UInt(1.W)))
  val waddr = IO(Input(UInt(addressWidth.W)))
  val wdata = IO(Input(UInt(dataWidth.W)))

  val core = Module(new RF1R1WRamBase(addressWidth = addressWidth, dataWidth = dataWidth))
  core.io.clk := clock
  core.io.reset := reset
  core.io.re := 1.B
  core.io.raddr := raddr
  rdata := core.io.rdata
  core.io.we := we
  core.io.waddr := waddr
  core.io.wdata := wdata
}

// 1-Cycle Fixed Latency
class RF1R1WRamGen[D <: Data](addressWidth: Int, gen: D) extends Module {
  val raddr = IO(Input(UInt(addressWidth.W)))
  val rdata = IO(Output(gen))
  val we = IO(Input(UInt(1.W)))
  val waddr = IO(Input(UInt(addressWidth.W)))
  val wdata = IO(Input(gen))

  val core = Module(new RF1R1WRamBase(addressWidth = addressWidth, dataWidth = gen.getWidth))
  core.io.clk := clock
  core.io.reset := reset
  core.io.re := 1.B
  core.io.raddr := raddr
  rdata := core.io.rdata.asTypeOf(gen)
  core.io.we := we
  core.io.waddr := waddr
  core.io.wdata := wdata.asUInt
}

// 1-Cycle Fixed Latency with clock enable
class RF1R1WRamGenEN[D <: Data](addressWidth: Int, gen: D) extends Module {
  val re = IO(Input(UInt(1.W)))
  val raddr = IO(Input(UInt(addressWidth.W)))
  val rdata = IO(Output(gen))
  val we = IO(Input(UInt(1.W)))
  val waddr = IO(Input(UInt(addressWidth.W)))
  val wdata = IO(Input(gen))

  val core = Module(new RF1R1WRamBase(addressWidth = addressWidth, dataWidth = gen.getWidth))
  core.io.clk := clock
  core.io.reset := reset
  core.io.re := re
  core.io.raddr := raddr
  rdata := core.io.rdata.asTypeOf(gen)
  core.io.we := we
  core.io.waddr := waddr
  core.io.wdata := wdata.asUInt
}


// 0-Cycle Fixed Latency
class RF1R1RWAsyncRamBase(addressWidth: Int, dataWidth: Int, selfReset: Int) extends
  BlackBox(Map("addressWidth" -> addressWidth, "dataWidth" -> dataWidth, "selfReset" -> selfReset)) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Reset())

    val raddr = Input(UInt(addressWidth.W))
    val rdata = Output(UInt(dataWidth.W))

    val we = Input(UInt(1.W))
    val waddr = Input(UInt(addressWidth.W))
    val wdata = Input(UInt(dataWidth.W))
    val rwdata = Output(UInt(dataWidth.W))
  })
  addResource("/verilog/RF1R1RWAsyncRamBase.sv")
}

// 0-Cycle Fixed Latency
class RF1R1RWAsyncRam(addressWidth: Int, dataWidth: Int, selfReset: Int) extends Module {
  val raddr = IO(Input(UInt(addressWidth.W)))
  val rdata = IO(Output(UInt(dataWidth.W)))
  val we = IO(Input(UInt(1.W)))
  val waddr = IO(Input(UInt(addressWidth.W)))
  val wdata = IO(Input(UInt(dataWidth.W)))
  val rwdata = IO(Output(UInt(dataWidth.W)))

  val core = Module(new RF1R1RWAsyncRamBase(addressWidth = addressWidth, dataWidth = dataWidth, selfReset = selfReset))
  core.io.clk := clock
  core.io.reset := reset
  core.io.raddr := raddr
  rdata := core.io.rdata
  core.io.we := we
  core.io.waddr := waddr
  core.io.wdata := wdata
  rwdata := core.io.rwdata
}

// 1-Cycle Fixed Latency
class RF1R1RWAsyncRamGen[D <: Data](addressWidth: Int, selfReset: Int, gen: D) extends Module {
  val raddr = IO(Input(UInt(addressWidth.W)))
  val rdata = IO(Output(gen))
  val we = IO(Input(UInt(1.W)))
  val waddr = IO(Input(UInt(addressWidth.W)))
  val wdata = IO(Input(gen))
  val rwdata = IO(Output(gen))

  val core = Module(new RF1R1RWAsyncRamBase(addressWidth = addressWidth, dataWidth = gen.getWidth, selfReset = selfReset))
  core.io.clk := clock
  core.io.reset := reset
  core.io.raddr := raddr
  rdata := core.io.rdata.asTypeOf(gen)
  core.io.we := we
  core.io.waddr := waddr
  core.io.wdata := wdata.asUInt
  rwdata := core.io.rwdata.asTypeOf(gen)
}

// 0-Cycle Fixed Latency
class RF1RWAsyncRamBase(addressWidth: Int, dataWidth: Int, selfReset: Int) extends
  BlackBox(Map("addressWidth" -> addressWidth, "dataWidth" -> dataWidth, "selfReset" -> selfReset)) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Reset())

    val we = Input(UInt(1.W))
    val waddr = Input(UInt(addressWidth.W))
    val wdata = Input(UInt(dataWidth.W))
    val rwdata = Output(UInt(dataWidth.W))
  })
  addResource("/verilog/RF1RWAsyncRamBase.sv")
}

// 0-Cycle Fixed Latency
class RF1RWAsyncRam(addressWidth: Int, dataWidth: Int, selfReset: Int) extends Module {
  val we = IO(Input(UInt(1.W)))
  val waddr = IO(Input(UInt(addressWidth.W)))
  val wdata = IO(Input(UInt(dataWidth.W)))
  val rwdata = IO(Output(UInt(dataWidth.W)))

  val core = Module(new RF1RWAsyncRamBase(addressWidth = addressWidth, dataWidth = dataWidth, selfReset = selfReset))
  core.io.clk := clock
  core.io.reset := reset
  core.io.we := we
  core.io.waddr := waddr
  core.io.wdata := wdata
  rwdata := core.io.rwdata
}

