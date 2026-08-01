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

package fpexputils

import chisel3._
import chisel3.util._

class LeftShifter extends Module {
    val io = IO(new Bundle {
        val X = Input(UInt(24.W))
        val S = Input(UInt(6.W))
        val R = Output(UInt(57.W)) // 56 downto 0
    })

    // 内部信号
    val ps     = Wire(UInt(6.W))
    val level0 = Wire(UInt(24.W))
    val level1 = Wire(UInt(25.W)) // 24 downto 0
    val level2 = Wire(UInt(27.W)) // 26 downto 0
    val level3 = Wire(UInt(31.W)) // 30 downto 0
    val level4 = Wire(UInt(39.W)) // 38 downto 0
    val level5 = Wire(UInt(55.W)) // 54 downto 0
    val level6 = Wire(UInt(87.W)) // 86 downto 0

    // 组合逻辑
    ps     := io.S
    level0 := io.X

    // 第1级移位：根据ps(0)决定是否移位1位
    level1 := Mux(
      ps(0),
      Cat(level0, 0.U(1.W)), // 左移1位
      Cat(0.U(1.W), level0)  // 不移位，只是扩展1位
    )

    // 第2级移位：根据ps(1)决定是否移位2位
    level2 := Mux(
      ps(1),
      Cat(level1, 0.U(2.W)), // 左移2位
      Cat(0.U(2.W), level1)  // 不移位，扩展2位
    )

    // 第3级移位：根据ps(2)决定是否移位4位
    level3 := Mux(
      ps(2),
      Cat(level2, 0.U(4.W)), // 左移4位
      Cat(0.U(4.W), level2)  // 不移位，扩展4位
    )

    // 第4级移位：根据ps(3)决定是否移位8位
    level4 := Mux(
      ps(3),
      Cat(level3, 0.U(8.W)), // 左移8位
      Cat(0.U(8.W), level3)  // 不移位，扩展8位
    )

    // 第5级移位：根据ps(4)决定是否移位16位
    level5 := Mux(
      ps(4),
      Cat(level4, 0.U(16.W)), // 左移16位
      Cat(0.U(16.W), level4)  // 不移位，扩展16位
    )

    // 第6级移位：根据ps(5)决定是否移位32位
    level6 := Mux(
      ps(5),
      Cat(level5, 0.U(32.W)), // 左移32位
      Cat(0.U(32.W), level5)  // 不移位，扩展32位
    )

    // 输出结果（取低57位）
    io.R := level6(56, 0)
}
