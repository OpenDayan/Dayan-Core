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

package rvtng.uram

import chisel3._

// Advanced URAM:
//   Independent Address for each bank
//   Allowing Scatting / Gathering and Swizzling Access mode

// Maybe: Fused URAM with L1 Cache. Some part of URAM Can be Config to used as L1 Cache Instead of URAM

// Interface Define of AdvURAM
// Use ID To track Coming Port or other out-standing information.
class AdvURAMReq(val addrWidth: Int, val idWidth: Int, val dataWidth: Int) extends Bundle {
  val address = UInt(addrWidth.W)
  val id = UInt(idWidth.W)
  val byteEnable = UInt((dataWidth/8).W)
}

class AdvURAMResp(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()
  val data    = UInt(dataWidth.W)
  val id = UInt(idWidth.W)
}

class AdvURAMWResp(addrWidth: Int, idWidth: Int, dataWidth: Int) extends Bundle {
  val valid   = Bool()
  val id = UInt(idWidth.W)
}


