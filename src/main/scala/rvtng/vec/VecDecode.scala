package rvtng.vec

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import _root_.circt.stage.ChiselStage

object VecInst {
  val v_load           = BitPat("b010000_?_?????_00000_00000000_???????")
  val s_load           = BitPat("b010000_?_?????_00001_00000000_???????")
  val const_load       = BitPat("b010000_?_?????_00010_00000000_???????")
  val v_store          = BitPat("b010001_0_?????_?????_00000000_???????")
  val s_store          = BitPat("b010001_0_?????_?????_00000010_???????")

  val vv_int32_rol     = BitPat("b100001_?_00000_?????_000_?????_???????")
  val vv_int32_sll     = BitPat("b100001_?_00000_?????_001_?????_???????")
  val vv_int32_sra     = BitPat("b100001_?_00000_?????_010_?????_???????")
  val vv_int32_srl     = BitPat("b100001_?_00000_?????_011_?????_???????")
  val vv_int32_add     = BitPat("b100001_?_00000_?????_100_?????_???????")
  val vv_int32_sub     = BitPat("b100001_?_00000_?????_101_?????_???????")
  val vv_int32_mul24     = BitPat("b100001_?_00000_?????_110_?????_???????")

  val vv_int32_cmp     = BitPat("b100001_?_00001_?????_???_?????_???????")
  val vv_int32_bitwise = BitPat("b100001_?_0001?_?????_???_?????_???????")

  val vv_mux2          = BitPat("b111111_?_00000_?????_???_?????_???????")

  val v_int32_cmp      = BitPat("b100000_?_00001_00000_???_?????_???????")
  val v_int32_sll      = BitPat("b100000_?_00001_001_?????_?????_???????")
  val v_int32_sra      = BitPat("b100000_?_00001_010_?????_?????_???????")
  val v_int32_srl      = BitPat("b100000_?_00001_011_?????_?????_???????")

  val v_comb_2x2B      = BitPat("b100000_?_01111_0000001_?_?????_???????")
  val v_comb_4x1B      = BitPat("b100000_?_01111_000001_??_?????_???????")
  val v_separate_2x2B  = BitPat("b100000_?_01111_0000101_?_?????_???????")
  val v_separate_4x1B  = BitPat("b100000_?_01111_000011_??_?????_???????")

  val cond_bitwise     = BitPat("b100000_0_00000000_????_???_???_???????")

  // Configuration Instructions
  val tcsr_write = BitPat("00000_01_??????????_000_?????_???????")
  val tcsr_read  = BitPat("00000_01_??????????_100_?????_???????")
  val sb_produce = BitPat("00001_01_0000000000_000_?????_???????")
  val sb_bar     = BitPat("00001_01_0000000000_001_?????_???????")

  val allVecInst = Seq(
    v_load, s_load, const_load, v_store, s_store,
    vv_int32_rol, vv_int32_sll, vv_int32_sra, vv_int32_srl,
    vv_int32_add, vv_int32_sub, vv_int32_mul24,
    vv_int32_cmp, vv_int32_bitwise,
    vv_mux2,
    v_int32_cmp, v_int32_sll, v_int32_sra, v_int32_srl,
    v_comb_2x2B, v_comb_4x1B, v_separate_2x2B, v_separate_4x1B,
    cond_bitwise,
    tcsr_write, tcsr_read, sb_produce, sb_bar
  )
}

object VecFuEnum extends ChiselEnum {
  val Int = Value(0.U)
  val Mov = Value(1.U)
  val Cmp = Value(2.U)
  val Lsu = Value(3.U)
  val Csr = Value(4.U)
}

class VecDecodedFixed(params: VecParams) extends Bundle {
  val fu = VecFuEnum()
  val optionalLatency = UInt(params.log2MaxLatency.W)
  val vsrc1v = Bool()
  val vsrc2v = Bool()
  val vdstv = Bool()
  val condr1v = Bool()
  val condr2v = Bool()
  val condwv = Bool()
  val finishHint = Bool()
  val optype = UInt(6.W)
}

class VecDecoded(params: VecParams) extends VecDecodedFixed(params) {
  val vsrc1  = UInt(5.W)
  val vsrc2  = UInt(5.W)
  val vdst   = UInt(1.W)
  val inst14_12 = UInt(3.W)
  val inst24_20 = UInt(5.W)
//  val condr1 = UInt(5.W)
//  val condr2 = UInt(5.W)
}

object VecInstRebuild {
  def apply(in: VecInstWSchedule): UInt = {
    Cat(0b000000.U(6.W), in.vdst, in.inst24_20, in.vsrc2, in.inst14_12, in.vsrc1, 0b0001011.U(7.W))
  }

  def apple(in: VecDecoded): UInt = {
    Cat(0b000000.U(6.W), in.vdst, in.inst24_20, in.vsrc2, in.inst14_12, in.vsrc1, 0b0001011.U(7.W))
  }
}

class VecDecode(params: VecParams) extends Module{
  val inst = IO(Input(UInt(32.W)))
  val decode = IO(Output(new VecDecoded(params)))

  // TODO: In Newer version of Chisel, BitPat of Enum is already supported feature.
  def enumToUInt(x: EnumType): UInt = {
    x.litValue.U((x.getWidth).W)
  }

  val fuDecodeMap = Seq(
    VecInst.v_load -> BitPat(enumToUInt(VecFuEnum.Lsu)), VecInst.s_load -> BitPat(enumToUInt(VecFuEnum.Lsu)),
    VecInst.const_load -> BitPat(enumToUInt(VecFuEnum.Lsu)),
    VecInst.v_store -> BitPat(enumToUInt(VecFuEnum.Lsu)), VecInst.s_store -> BitPat(enumToUInt(VecFuEnum.Lsu)),

    VecInst.vv_int32_rol -> BitPat(enumToUInt(VecFuEnum.Int)), VecInst.vv_int32_sll -> BitPat(enumToUInt(VecFuEnum.Int)),
    VecInst.vv_int32_sra -> BitPat(enumToUInt(VecFuEnum.Int)), VecInst.vv_int32_srl -> BitPat(enumToUInt(VecFuEnum.Int)),
    VecInst.vv_int32_add -> BitPat(enumToUInt(VecFuEnum.Int)), VecInst.vv_int32_sub -> BitPat(enumToUInt(VecFuEnum.Int)),
    VecInst.vv_int32_mul24 -> BitPat(enumToUInt(VecFuEnum.Int)), VecInst.vv_int32_bitwise -> BitPat(enumToUInt(VecFuEnum.Int)),

    VecInst.v_int32_sll -> BitPat(enumToUInt(VecFuEnum.Int)), VecInst.v_int32_sra -> BitPat(enumToUInt(VecFuEnum.Int)),
    VecInst.v_int32_srl -> BitPat(enumToUInt(VecFuEnum.Int)),

    VecInst.vv_int32_cmp -> BitPat(enumToUInt(VecFuEnum.Cmp)), VecInst.v_int32_cmp -> BitPat(enumToUInt(VecFuEnum.Cmp)),
    VecInst.cond_bitwise -> BitPat(enumToUInt(VecFuEnum.Cmp)),

    VecInst.vv_mux2 -> BitPat(enumToUInt(VecFuEnum.Mov)),
    VecInst.v_comb_2x2B -> BitPat(enumToUInt(VecFuEnum.Mov)),
    VecInst.v_comb_4x1B -> BitPat(enumToUInt(VecFuEnum.Mov)),
    VecInst.v_separate_2x2B -> BitPat(enumToUInt(VecFuEnum.Mov)),
    VecInst.v_separate_4x1B -> BitPat(enumToUInt(VecFuEnum.Mov))
  )

  val opTable = Seq(
    VecInst.v_load -> BitPat("b000000"), VecInst.s_load -> BitPat("b000001"),
    VecInst.const_load -> BitPat("b000011"),
    VecInst.v_store -> BitPat("b100000"), VecInst.s_store -> BitPat("b100001"),

    VecInst.vv_int32_rol -> BitPat("b000000"), VecInst.vv_int32_sll -> BitPat("b000001"),
    VecInst.vv_int32_sra -> BitPat("b000010"), VecInst.vv_int32_srl -> BitPat("b000011"),
    VecInst.vv_int32_add -> BitPat("b000100"), VecInst.vv_int32_sub -> BitPat("b000101"),
    VecInst.vv_int32_mul24 -> BitPat("b000110"), VecInst.vv_int32_bitwise -> BitPat("b010000"),

    VecInst.v_int32_sll -> BitPat("b001001"), VecInst.v_int32_sra -> BitPat("b001010"),
    VecInst.v_int32_srl -> BitPat("b001011"),

    VecInst.vv_int32_cmp -> BitPat("b000000"), VecInst.v_int32_cmp -> BitPat("b000001"),
    VecInst.cond_bitwise -> BitPat("b100000"),

    VecInst.vv_mux2         -> BitPat("b000000"),
    VecInst.v_comb_2x2B     -> BitPat("b000010"), VecInst.v_comb_4x1B     -> BitPat("b000011"),
    VecInst.v_separate_2x2B -> BitPat("b000100"), VecInst.v_separate_4x1B -> BitPat("b000101")
  )

  val latencyDecodeMap: Seq[(BitPat, BitPat)] = fuDecodeMap.map(it => {
    if(it._2 == BitPat(enumToUInt(VecFuEnum.Int))) {
      it._1 -> BitPat(1.U(params.log2MaxLatency.W))
    } else if(it._2 == BitPat(enumToUInt(VecFuEnum.Mov))) {
      it._1 -> BitPat(2.U(params.log2MaxLatency.W))
    } else if(it._2 == BitPat(enumToUInt(VecFuEnum.Cmp))) {
      it._1 -> BitPat(1.U(params.log2MaxLatency.W))
    } else if(it._2 == BitPat(enumToUInt(VecFuEnum.Lsu))) {
      it._1 -> BitPat(0.U(params.log2MaxLatency.W))
    } else if(it._2 == BitPat(enumToUInt(VecFuEnum.Csr))) {
      it._1 -> BitPat(0.U(params.log2MaxLatency.W))
    } else {
      it._1 -> BitPat(0.U(params.log2MaxLatency.W)) // Variable Latency
    }
  })

  // 使用 vsrc1 的指令
  val vsrc1vInst = Seq(
    VecInst.vv_int32_rol, VecInst.vv_int32_sll, VecInst.vv_int32_sra, VecInst.vv_int32_srl,
    VecInst.vv_int32_add, VecInst.vv_int32_sub, VecInst.vv_int32_mul24, VecInst.vv_int32_cmp,
    VecInst.vv_int32_bitwise, VecInst.vv_mux2, VecInst.v_int32_cmp,
    VecInst.v_int32_sll, VecInst.v_int32_sra, VecInst.v_int32_srl,
    VecInst.v_comb_2x2B, VecInst.v_comb_4x1B, VecInst.v_separate_2x2B, VecInst.v_separate_4x1B
  )
  // 使用 vsrc2 的指令
  val vsrc2vInst = Seq(
    VecInst.v_store, VecInst.s_store,
    VecInst.vv_int32_rol, VecInst.vv_int32_sll, VecInst.vv_int32_sra, VecInst.vv_int32_srl,
    VecInst.vv_int32_add, VecInst.vv_int32_sub, VecInst.vv_int32_mul24, VecInst.vv_int32_cmp,
    VecInst.vv_int32_bitwise, VecInst.vv_mux2
  )
  // 使用 vdst 的指令
  val vdstvInst = Seq(
    VecInst.v_load, VecInst.s_load, VecInst.const_load,
    VecInst.vv_int32_rol, VecInst.vv_int32_sll, VecInst.vv_int32_sra, VecInst.vv_int32_srl,
    // Note: CMP Does Not Writeback to VREG!
    VecInst.vv_int32_add, VecInst.vv_int32_sub, VecInst.vv_int32_mul24, // VecInst.vv_int32_cmp,
    VecInst.vv_int32_bitwise, VecInst.vv_mux2, // VecInst.v_int32_cmp,
    VecInst.v_int32_sll, VecInst.v_int32_sra, VecInst.v_int32_srl,
    VecInst.v_comb_2x2B, VecInst.v_comb_4x1B, VecInst.v_separate_2x2B, VecInst.v_separate_4x1B
  )
  // 使用 cond1 的指令
  val condr1vInst = Seq(VecInst.vv_mux2, VecInst.cond_bitwise)
  // 使用 cond2 的指令
  val condr2vInst = Seq(VecInst.cond_bitwise)
  // 写回 cond 的指令
  val condwvInst  = Seq(VecInst.cond_bitwise, VecInst.vv_int32_cmp, VecInst.v_int32_cmp)

  // 带有 Finish 标记的指令
  val finishInst = Seq(VecInst.tcsr_write, VecInst.tcsr_read, VecInst.sb_produce, VecInst.sb_bar)

  def generateBoolDecodeTable(yInst: Seq[BitPat]): Seq[(BitPat, BitPat)] = {
    VecInst.allVecInst.map(it => {
      if(yInst.contains(it)) {
        it -> BitPat(1.B)
      }
      else {
        it -> BitPat(0.B)
      }
    })
  }

  decode.fu := decoder(QMCMinimizer, inst,
    TruthTable(fuDecodeMap, BitPat.dontCare(decode.fu.getWidth))).asTypeOf(decode.fu)
  decode.optionalLatency := decoder(QMCMinimizer, inst,
    TruthTable(latencyDecodeMap, BitPat.dontCare(decode.optionalLatency.getWidth)))
  decode.vsrc1v := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(vsrc1vInst), BitPat(0.B)))
  decode.vsrc2v := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(vsrc2vInst), BitPat(0.B)))
  decode.vdstv := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(vdstvInst), BitPat(0.B)))
  decode.condr1v := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(condr1vInst), BitPat(0.B)))
  decode.condr2v := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(condr2vInst), BitPat(0.B)))
  decode.condwv := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(condwvInst), BitPat(0.B)))
  decode.optype := decoder(QMCMinimizer, inst,
    TruthTable(opTable, BitPat.dontCare(6)))
  decode.finishHint := decoder(QMCMinimizer, inst,
    TruthTable(generateBoolDecodeTable(finishInst), BitPat(0.B)))

  decode.vsrc1     := inst(11,  7)
  decode.inst14_12 := inst(14, 12)
  decode.inst24_20 := inst(24, 20)
  decode.vsrc2     := inst(19, 15)
  decode.vdst      := inst(25)
//  decode.condr1 := Mux(decode.condr2v, inst(9, 7), inst(14, 12))
//  decode.condr2 := inst(12,  9)
//  decode.imm := inst(17, 12)
}

object VecDecode extends App {
  ChiselStage.emitSystemVerilog(
    new VecDecode(VecParams()),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/VecDecode"
    )
  )
}
