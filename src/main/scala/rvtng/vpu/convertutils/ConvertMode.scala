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

package convertutils

import chisel3._

object ConvertMode {
  val TX_CONVERT_ROUND = 0.U
  val TX_CONVERT_FLOOR = 1.U
  val TX_CONVERT_CEIL  = 2.U
}

// NEMU C code:
// typedef enum __TX_CONVERT_MODE {
//   TX_CONVERT_ROUND = 0,
//   TX_CONVERT_FLOOR = 1,
//   TX_CONVERT_CEIL = 2
// } ConvertMode;