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

package rvtng.top

import chisel3._

class TensorInst extends Bundle {
  val instWord = UInt(64.W)
  val rs1Data = UInt(64.W) // Based Address of A
  val rs2Data = UInt(64.W) // Based Address of B
  val rdData  = UInt(64.W)
}

class TensorResp extends Bundle {
  val data = UInt(64.W)
  val rd   = UInt(5.W)
}

class ScoreboardComp extends Bundle {
  val entryId = UInt(64.W)
}

