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

package rvtng.bus

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._

import scala.collection.immutable.ListMap

case class AXI4BundleParameters(
                               addressBits: Int,
                               dataBits:    Int,
                               idBits:      Int)
{
  // Chisel has issues with 0-width wires
  require (addressBits >= 1)
  require (dataBits    >= 8)
  require (idBits      >= 1)
  val addrLoBits = log2Up(dataBits/8)

  // Used to uniquify bus IP names
  def shortName = s"a${addressBits}d${dataBits}i${idBits}" + "axi4"
}

class AXI4AXBundle(private val params: AXI4BundleParameters) extends Bundle {
  val addr  = UInt(params.addressBits.W)
  val id    = UInt(params.idBits.W)
  val size  = UInt(3.W)
  val len   = UInt(8.W)
  val burst = UInt(2.W)
}

class AXI4RBundle(private val params: AXI4BundleParameters) extends Bundle {
  val id   = UInt(params.idBits.W)
  val resp = UInt(2.W)
  val data = UInt(params.dataBits.W)
  val last = Bool()
}

class AXI4WBundle(private val params: AXI4BundleParameters) extends Bundle {
  val strb = UInt((params.dataBits / 8).W)
  val data = UInt(params.dataBits.W)
  val last = Bool()
}

class AXI4BBundle(private val params: AXI4BundleParameters) extends Bundle {
  val id   = UInt(params.idBits.W)
  val resp = UInt(2.W)
}

class AXI4Bundle(val params: AXI4BundleParameters) extends Bundle {
  val ar = Decoupled(new AXI4AXBundle(params))
  val aw = Decoupled(new AXI4AXBundle(params))
  val w  = Decoupled(new AXI4WBundle(params))
  val r  = Flipped(Decoupled(new AXI4RBundle(params)))
  val b  = Flipped(Decoupled(new AXI4BBundle(params)))
}