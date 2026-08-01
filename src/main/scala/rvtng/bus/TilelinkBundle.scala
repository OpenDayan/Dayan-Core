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

// See LICENSE.SiFive for license details.

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._

import scala.collection.immutable.ListMap

case class TLBundleParameters(
                               addressBits: Int,
                               dataBits:    Int,
                               sourceBits:  Int,
                               sinkBits:    Int,
                               sizeBits:    Int,
                               hasBCE: Boolean)
{
  // Chisel has issues with 0-width wires
  require (addressBits >= 1)
  require (dataBits    >= 8)
  require (sourceBits  >= 1)
  require (sinkBits    >= 1)
  require (sizeBits    >= 1)
  val addrLoBits = log2Up(dataBits/8)

  // Used to uniquify bus IP names
  def shortName = s"a${addressBits}d${dataBits}s${sourceBits}k${sinkBits}z${sizeBits}" + (if (hasBCE) "c" else "u")
}

abstract class TLBundleBase(val params: TLBundleParameters) extends Bundle

// common combos in lazy policy:
//   Put + Acquire
//   Release + AccessAck

object TLMessages
{
  //                                  A    B    C    D    E
  def PutFullData    = 0.U     //     .    .                   => AccessAck
  def PutPartialData = 1.U     //     .    .                   => AccessAck
  def ArithmeticData = 2.U     //     .    .                   => AccessAckData
  def LogicalData    = 3.U     //     .    .                   => AccessAckData
  def Get            = 4.U     //     .    .                   => AccessAckData
  def Hint           = 5.U     //     .    .                   => HintAck
  def AcquireBlock   = 6.U     //     .                        => Grant[Data]
  def AcquirePerm    = 7.U     //     .                        => Grant[Data]
  def Probe          = 6.U     //          .                   => ProbeAck[Data]
  def AccessAck      = 0.U     //               .    .
  def AccessAckData  = 1.U     //               .    .
  def HintAck        = 2.U     //               .    .
  def ProbeAck       = 4.U     //               .
  def ProbeAckData   = 5.U     //               .
  def Release        = 6.U     //               .              => ReleaseAck
  def ReleaseData    = 7.U     //               .              => ReleaseAck
  def Grant          = 4.U     //                    .         => GrantAck
  def GrantData      = 5.U     //                    .         => GrantAck
  def ReleaseAck     = 6.U     //                    .
  def GrantAck       = 0.U     //                         .

  def isA(x: UInt) = x <= AcquirePerm
  def isB(x: UInt) = x <= Probe
  def isC(x: UInt) = x <= ReleaseData
  def isD(x: UInt) = x <= ReleaseAck

  def adResponse = VecInit(AccessAck, AccessAck, AccessAckData, AccessAckData, AccessAckData, HintAck, Grant, Grant)
  def bcResponse = VecInit(AccessAck, AccessAck, AccessAckData, AccessAckData, AccessAckData, HintAck, ProbeAck, ProbeAck)

  def a = Seq( ("PutFullData",TLPermissions.PermMsgReserved),
    ("PutPartialData",TLPermissions.PermMsgReserved),
    ("ArithmeticData",TLAtomics.ArithMsg),
    ("LogicalData",TLAtomics.LogicMsg),
    ("Get",TLPermissions.PermMsgReserved),
    ("Hint",TLHints.HintsMsg),
    ("AcquireBlock",TLPermissions.PermMsgGrow),
    ("AcquirePerm",TLPermissions.PermMsgGrow))

  def b = Seq( ("PutFullData",TLPermissions.PermMsgReserved),
    ("PutPartialData",TLPermissions.PermMsgReserved),
    ("ArithmeticData",TLAtomics.ArithMsg),
    ("LogicalData",TLAtomics.LogicMsg),
    ("Get",TLPermissions.PermMsgReserved),
    ("Hint",TLHints.HintsMsg),
    ("Probe",TLPermissions.PermMsgCap))

  def c = Seq( ("AccessAck",TLPermissions.PermMsgReserved),
    ("AccessAckData",TLPermissions.PermMsgReserved),
    ("HintAck",TLPermissions.PermMsgReserved),
    ("Invalid Opcode",TLPermissions.PermMsgReserved),
    ("ProbeAck",TLPermissions.PermMsgReport),
    ("ProbeAckData",TLPermissions.PermMsgReport),
    ("Release",TLPermissions.PermMsgReport),
    ("ReleaseData",TLPermissions.PermMsgReport))

  def d = Seq( ("AccessAck",TLPermissions.PermMsgReserved),
    ("AccessAckData",TLPermissions.PermMsgReserved),
    ("HintAck",TLPermissions.PermMsgReserved),
    ("Invalid Opcode",TLPermissions.PermMsgReserved),
    ("Grant",TLPermissions.PermMsgCap),
    ("GrantData",TLPermissions.PermMsgCap),
    ("ReleaseAck",TLPermissions.PermMsgReserved))

}

/**
 * The three primary TileLink permissions are:
 *   (T)runk: the agent is (or is on inwards path to) the global point of serialization.
 *   (B)ranch: the agent is on an outwards path to
 *   (N)one:
 * These permissions are permuted by transfer operations in various ways.
 * Operations can cap permissions, request for them to be grown or shrunk,
 * or for a report on their current status.
 */
object TLPermissions
{
  val aWidth = 2
  val bdWidth = 2
  val cWidth = 3

  // Cap types (Grant = new permissions, Probe = permisions <= target)
  def toT = 0.U(bdWidth.W)
  def toB = 1.U(bdWidth.W)
  def toN = 2.U(bdWidth.W)
  def isCap(x: UInt) = x <= toN

  // Grow types (Acquire = permissions >= target)
  def NtoB = 0.U(aWidth.W)
  def NtoT = 1.U(aWidth.W)
  def BtoT = 2.U(aWidth.W)
  def isGrow(x: UInt) = x <= BtoT

  // Shrink types (ProbeAck, Release)
  def TtoB = 0.U(cWidth.W)
  def TtoN = 1.U(cWidth.W)
  def BtoN = 2.U(cWidth.W)
  def isShrink(x: UInt) = x <= BtoN

  // Report types (ProbeAck, Release)
  def TtoT = 3.U(cWidth.W)
  def BtoB = 4.U(cWidth.W)
  def NtoN = 5.U(cWidth.W)
  def isReport(x: UInt) = x <= NtoN

  def PermMsgGrow:Seq[String] = Seq("Grow NtoB", "Grow NtoT", "Grow BtoT")
  def PermMsgCap:Seq[String] = Seq("Cap toT", "Cap toB", "Cap toN")
  def PermMsgReport:Seq[String] = Seq("Shrink TtoB", "Shrink TtoN", "Shrink BtoN", "Report TotT", "Report BtoB", "Report NtoN")
  def PermMsgReserved:Seq[String] = Seq("Reserved")
}

object TLAtomics
{
  val width = 3

  // Arithmetic types
  def MIN  = 0.U(width.W)
  def MAX  = 1.U(width.W)
  def MINU = 2.U(width.W)
  def MAXU = 3.U(width.W)
  def ADD  = 4.U(width.W)
  def isArithmetic(x: UInt) = x <= ADD

  // Logical types
  def XOR  = 0.U(width.W)
  def OR   = 1.U(width.W)
  def AND  = 2.U(width.W)
  def SWAP = 3.U(width.W)
  def isLogical(x: UInt) = x <= SWAP

  def ArithMsg:Seq[String] = Seq("MIN", "MAX", "MINU", "MAXU", "ADD")
  def LogicMsg:Seq[String] = Seq("XOR", "OR", "AND", "SWAP")
}


object TLHints
{
  val width = 1

  def PREFETCH_READ  = 0.U(width.W)
  def PREFETCH_WRITE = 1.U(width.W)
  def isHints(x: UInt) = x <= PREFETCH_WRITE

  def HintsMsg:Seq[String] = Seq("PrefetchRead", "PrefetchWrite")
}

sealed trait TLChannel extends TLBundleBase {
  val channelName: String
}

sealed trait TLDataChannel extends TLChannel
sealed trait TLAddrChannel extends TLDataChannel

final class TLBundleA(params: TLBundleParameters)
  extends TLBundleBase(params) with TLAddrChannel
{
  override def typeName = s"TLBundleA_${params.shortName}"
  val channelName = "'A' channel"
  // fixed fields during multibeat:
  val opcode  = UInt(3.W)
  val param   = UInt(List(TLAtomics.width, TLPermissions.aWidth, TLHints.width).max.W) // amo_opcode || grow perms || hint
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W) // from
  val address = UInt(params.addressBits.W) // to
  // variable fields during multibeat:
  val mask    = UInt((params.dataBits/8).W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool() // only applies to *Data messages
}
final class TLBundleB(params: TLBundleParameters)
  extends TLBundleBase(params) with TLAddrChannel
{
  override def typeName = s"TLBundleB_${params.shortName}"
  val channelName = "'B' channel"
  // fixed fields during multibeat:
  val opcode  = UInt(3.W)
  val param   = UInt(TLPermissions.bdWidth.W) // cap perms
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W) // to
  val address = UInt(params.addressBits.W) // from
  // variable fields during multibeat:
  val mask    = UInt((params.dataBits/8).W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool() // only applies to *Data messages
}

final class TLBundleC(params: TLBundleParameters)
  extends TLBundleBase(params) with TLAddrChannel
{
  override def typeName = s"TLBundleC_${params.shortName}"
  val channelName = "'C' channel"
  // fixed fields during multibeat:
  val opcode  = UInt(3.W)
  val param   = UInt(TLPermissions.cWidth.W) // shrink or report perms
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W) // from
  val address = UInt(params.addressBits.W) // to
  // variable fields during multibeat:
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool() // only applies to *Data messages
}

final class TLBundleD(params: TLBundleParameters)
  extends TLBundleBase(params) with TLDataChannel
{
  override def typeName = s"TLBundleD_${params.shortName}"
  val channelName = "'D' channel"
  // fixed fields during multibeat:
  val opcode  = UInt(3.W)
  val param   = UInt(TLPermissions.bdWidth.W) // cap perms
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W) // to
  val sink    = UInt(params.sinkBits.W)   // from
  val denied  = Bool() // implies corrupt iff *Data
  // variable fields during multibeat:
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool() // only applies to *Data messages
}

final class TLBundleE(params: TLBundleParameters)
  extends TLBundleBase(params) with TLChannel
{
  override def typeName = s"TLBundleE_${params.shortName}"
  val channelName = "'E' channel"
  val sink = UInt(params.sinkBits.W) // to
}


class TLBundle(val params: TLBundleParameters) extends Record
{
  // Emulate a Bundle with elements abcde or ad depending on params.hasBCE

  private val optA = Some                (Decoupled(new TLBundleA(params)))
  private val optD = Some                (Flipped(Decoupled(new TLBundleD(params))))

//  private val optB = params.hasBCE.option(Flipped(Decoupled(new TLBundleB(params))))
//  private val optC = params.hasBCE.option(Decoupled(new TLBundleC(params)))
//  private val optE = params.hasBCE.option(Decoupled(new TLBundleE(params)))
  private val optB = if(params.hasBCE) Some(Flipped(Decoupled(new TLBundleB(params)))) else None
  private val optC = if(params.hasBCE) Some(Decoupled(new TLBundleC(params))) else None
  private val optE = if(params.hasBCE) Some(Decoupled(new TLBundleE(params))) else None

  def a: DecoupledIO[TLBundleA] = optA.getOrElse(WireDefault(0.U.asTypeOf(Decoupled(new TLBundleA(params)))))
  def b: DecoupledIO[TLBundleB] = optB.getOrElse(WireDefault(0.U.asTypeOf(Decoupled(new TLBundleB(params)))))
  def c: DecoupledIO[TLBundleC] = optC.getOrElse(WireDefault(0.U.asTypeOf(Decoupled(new TLBundleC(params)))))
  def d: DecoupledIO[TLBundleD] = optD.getOrElse(WireDefault(0.U.asTypeOf(Decoupled(new TLBundleD(params)))))
  def e: DecoupledIO[TLBundleE] = optE.getOrElse(WireDefault(0.U.asTypeOf(Decoupled(new TLBundleE(params)))))

  val elements =
    if (params.hasBCE) ListMap("e" -> e, "d" -> d, "c" -> c, "b" -> b, "a" -> a)
    else ListMap("d" -> d, "a" -> a)

  def tieoff(): Unit = {
    DataMirror.specifiedDirectionOf(a.ready) match {
      case SpecifiedDirection.Input =>
        a.ready := false.B
        c.ready := false.B
        e.ready := false.B
        b.valid := false.B
        d.valid := false.B
      case SpecifiedDirection.Output =>
        a.valid := false.B
        c.valid := false.B
        e.valid := false.B
        b.ready := false.B
        d.ready := false.B
      case _ =>
    }
  }
}

object TLBundle
{
  def apply(params: TLBundleParameters) = new TLBundle(params)
}
