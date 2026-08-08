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

package dayan.rvtng.bus

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam

class DPIAXI4Base(params: AXI4BundleParameters, Id: Int) extends BlackBox(Map(
                "ADDR_BITS" -> IntParam(params.addressBits),
                "DATA_BITS" -> IntParam(params.dataBits),
                "ID_BITS" -> IntParam(params.idBits),
                "ID" -> IntParam(Id)
              )) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val axi = Flipped(new AXI4Bundle(params))
  })

  addResource("/verilog/DPIAXI4Base.sv")
}

class DPIAXI4(params: AXI4BundleParameters, Id: Int) extends Module {
  val axi = IO(Flipped(new AXI4Bundle(params)))

  private val core = Module(new DPIAXI4Base(params, Id))
  core.io.clock := clock
  core.io.reset := reset
  core.io.axi <> axi
}
