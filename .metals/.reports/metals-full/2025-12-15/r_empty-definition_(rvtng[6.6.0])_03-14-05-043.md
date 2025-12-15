error id: file://<WORKSPACE>/src/main/scala/rvtng/vpu/ElewiseUnit.scala:rvtng/vpu/VpuParams#wblock_size.
file://<WORKSPACE>/src/main/scala/rvtng/vpu/ElewiseUnit.scala
empty definition using pc, found symbol in pc: 
found definition using semanticdb; symbol rvtng/vpu/VpuParams#wblock_size.
empty definition using fallback
non-local guesses:

offset: 959
uri: file://<WORKSPACE>/src/main/scala/rvtng/vpu/ElewiseUnit.scala
text:
```scala
package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._
import ujson.True

case class VpuParams(
                    sp_bank_entries: Int = 1024,
                    total_banks: Int = 1, // 最多同时 32 个并行线程
                    acc_width: Int = 32,
                    square_len: Int = 16,
                    computeunit_len: Int = 8,
                    tileRows: Int = 1,
                    tileColumns: Int = 1,
                    aligned_to : Int = 1, 
                    reservation_station_entries : Int = 48,
                    maxInstCount: Int = 512,
                    maxThreadCount: Int = 32, // 最多同时 32 个并行线程
                    maxLatency: Int = 8,
                    tcsrBanks: Int = 8,
                    laneWidth: Int = 128,
                    uramAddrBits: Int = 22,
                    sourceBits: Int = 1,
                    uramPortPerChannel: Int = 4
                    ) {
  val wblock_size@@ = square_len * tileRows
  val block_cols = square_len * tileColumns
  val block_size = computeunit_len
}

class ScratchpadReadReq(val n: Int) extends Bundle {
  val addr = UInt(log2Ceil(n).W)
  val fromDMA = Bool()
}

class ScratchpadReadResp(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val fromDMA = Bool()
}

class ScratchpadReadIO(val n: Int, val w: Int) extends Bundle {
  val req = Decoupled(new ScratchpadReadReq(n))
  val resp = Flipped(Decoupled(new ScratchpadReadResp(w)))
}

class ScratchpadWriteIO(val n: Int, val w: Int, val mask_len: Int) extends Bundle {
  val valid = Output(Bool())
  val ready = Input(Bool())
  val addr = Output(UInt(log2Ceil(n).W))
  val mask = Output(Vec(mask_len, Bool()))
  val data = Output(UInt(w.W))
  val acc = Output(Bool())
  def fire = valid && ready
}

class RoCCCommand extends Bundle {
  val inst = new RoCCInstruction
  val rs1 = Bits(64.W)
  val rs2 = Bits(64.W)
  val rs3 = Bits(64.W)
}

class tcsrcontent(w: Int) extends Bundle{
  val TCFG_DIM0 = UInt(w.W)
  val TCFG_STRIDE0 = UInt(w.W)
  val TCFG_DIM1 = UInt(w.W)
  val TCFG_STRIDE1 = UInt(w.W)
  val TCFG_DIM2 = UInt(w.W)
  val TCFG_STRIDE2 = UInt(w.W)
  val TCFG_DIM3 = UInt(w.W)
  val TCFG_STRIDE3 = UInt(w.W)
}

class GemminiCmd(rob_entries: Int) extends Bundle {
  val cmd = new RoCCCommand
  val tcfgrs = Vec(3, new tcsrcontent(32))
  val rob_id = UDValid(UInt(log2Up(rob_entries).W))
  val from_matmul_fsm = Bool()
  val from_conv_fsm = Bool()
}

class RoCCInstruction extends Bundle {
  val funct = Bits(8.W)
  val op = Bits(6.W)
  val reserve1 = Bits(5.W)
  val type_hi = Bits(4.W)
  val tcfgrs3 = Bits(3.W)
  val tcfgrs2 = Bits(3.W)
  val tcfgrs1 = Bits(3.W)
  val rs3 = Bits(5.W)
  val mode = Bits(2.W)
  val rs2 = Bits(5.W)
  val rs1 = Bits(5.W)
  // val type_lo = Bits(3.W)
  val xd = Bool()
  val xs1 = Bool()
  val xs2 = Bool()
  val rd = Bits(5.W)
  val opcode = Bits(7.W)
}

/* Provide tiling logic for all Vector Unit; Caculate address of each block in tiling; Provide data to each Vector Unit*/

class ElewiseUnit[T <: Data : Arithmetic](params: VpuParams) extends Module {

  import params._
  
  val io = IO(new Bundle {
    // NRAM接口
    val uram_read_1 = new ScratchpadReadIO(sp_bank_entries * total_banks, acc_width)

    val uram_read_2 = new ScratchpadReadIO(sp_bank_entries * total_banks, acc_width)

    val uram_write = new ScratchpadWriteIO(sp_bank_entries * total_banks, acc_width, (acc_width * computeunit_len / (aligned_to * 8)) max 1)

    
    // 控制信号
    val start      = Input(Bool())
    val cmd        = Input(new GemminiCmd(reservation_station_entries))
    
    // 状态信号
    val busy       = Output(Bool())
    val done       = Output(Bool())
  })

  val cmd_q = Module(new Queue(io.cmd.cloneType, 1))
  cmd_q.io.enq.valid := io.start
  cmd_q.io.enq.bits := io.cmd
  cmd_q.io.deq.ready := io.done

  val start = Wire(Bool())
  start := cmd_q.io.deq.valid
  
  val nramAddrWidth = 32
  val cmd = cmd_q.io.deq.bits

  val is_int = cmd.cmd.inst.funct === INT_OP
  val is_fp = cmd.cmd.inst.funct === FP_OP
  val is_sfu = cmd.cmd.inst.funct === SFU_OP
  val is_reduce = cmd.cmd.inst.funct === REDUCE_OP
  val is_convert = cmd.cmd.inst.funct === CONVERT_OP
  val ops_3 = is_int || is_fp
  val ops_2 = is_sfu || is_reduce || is_convert
  val reduce_dim = cmd.cmd.inst.asUInt(26,25) //unused 暂时只有行优先方向的reduce

  val reduce_first_time = Wire(Bool())
  val reduce_last_time = Wire(Bool())

  val last_block_size = Wire(UInt(16.W))

  val qDataA =  Module(new Queue(new Bundle {
    val vec = Vec(computeunit_len, UInt(32.W))
    val reduce_first_time = Bool()
    val reduce_last_time = Bool()
  }, 1, false, false))
  val qDataB =  Module(new Queue(Vec(computeunit_len, UInt(32.W)), 1, false, false))

  val ops3DataValid = qDataA.io.deq.valid && qDataB.io.deq.valid
  val ops2DataValid = qDataA.io.deq.valid

  val arbResult = Module(new Arbiter(Vec(computeunit_len, UInt(32.W)), 6))

  //  ElewiseIntOp
  // signal for ElewiseIntOp and ElewiseFpOp
  val nramAddrA  = Wire(UInt(nramAddrWidth.W))
  val nramAddrB  = Wire(UInt(nramAddrWidth.W))
  val nramAddrOut = Wire(UInt(nramAddrWidth.W))
  val opSel      = Wire(UInt(6.W))  // 000:add, 001:sub, 010:mul, 011:srl, 100:sra, 101:sll
  val broadcast  = Wire(UInt(4.W) ) // 00:无广播, 01:行广播, 10:列广播, 11:全广播
  val tcfgrs1    = Wire(new tcsrcontent(32))
  val tcfgrs2    = Wire(new tcsrcontent(32))
  val tcfgrs3    = Wire(new tcsrcontent(32))
  val opSel_reg = RegInit(0.U(6.W))

  when(start) {
    opSel_reg := opSel
  }

  nramAddrA := cmd.cmd.rs1
  nramAddrB := cmd.cmd.rs2
  nramAddrOut := cmd.cmd.rs3
  tcfgrs1 := io.cmd.tcfgrs(0)
  tcfgrs2 := io.cmd.tcfgrs(1)
  tcfgrs3 := io.cmd.tcfgrs(2)
  opSel := cmd.cmd.inst.asUInt(55,50)
  broadcast := cmd.cmd.inst.asUInt(48,45)

  val intType = SInt(32.W)
  val fpType = Float(8, 24)
  val e4m3Type = Float(4, 4)
  val bf16Type = Float(8, 8)

  val ElewiseInt = Module(new ElewiseIntOp(32, intType, computeunit_len))
  ElewiseInt.io.input.bits.a := qDataA.io.deq.bits.vec.asTypeOf(Vec(computeunit_len, intType))
  ElewiseInt.io.input.bits.b := qDataB.io.deq.bits.asTypeOf(Vec(computeunit_len, intType))
  ElewiseInt.io.input.bits.opSel := opSel
  ElewiseInt.io.input.bits.broadcastDim := broadcast
  ElewiseInt.io.input.valid := ops3DataValid && is_int

  val ElewiseFp = Module(new ElewiseFpOp(32, fpType, computeunit_len))
  ElewiseFp.io.input.bits.a := qDataA.io.deq.bits.vec.asTypeOf(Vec(computeunit_len, fpType))
  ElewiseFp.io.input.bits.b := qDataB.io.deq.bits.asTypeOf(Vec(computeunit_len, fpType))
  ElewiseFp.io.input.bits.opSel := opSel
  ElewiseFp.io.input.bits.broadcastDim := broadcast
  ElewiseFp.io.input.valid := ops3DataValid && is_fp

  val VSFU = Module(new VSFU(32, computeunit_len))
  VSFU.io.input.bits.a := qDataA.io.deq.bits.vec
  VSFU.io.input.bits.opSel := opSel
  VSFU.io.input.valid := ops2DataValid && is_sfu

  val ElewiseReduce = Module(new ReduceFpOp(32, fpType, computeunit_len))
  ElewiseReduce.io.input.bits.vecIn := qDataA.io.deq.bits.vec.asTypeOf(Vec(computeunit_len, fpType))
  ElewiseReduce.io.input.bits.opSel := opSel_reg
  ElewiseReduce.io.input.valid := ops2DataValid && is_reduce && ElewiseReduce.io.input.ready
  ElewiseReduce.io.input.bits.first_time := qDataA.io.deq.bits.reduce_first_time
  ElewiseReduce.io.input.bits.last_time := qDataA.io.deq.bits.reduce_last_time

  
  import convertutils._
  import convertutils.ConvertType._
  import convertutils.ConvertMode._
  val convertMode    = Wire(UInt(2.W))
  val convertInType  = Wire(UInt(7.W))
  val convertOutType = Wire(UInt(7.W))
  convertMode        := cmd.cmd.inst.asUInt(26,25)
  convertInType      := Cat( cmd.cmd.inst.asUInt(44,41), cmd.cmd.inst.asUInt(14,12) )
  convertOutType     := cmd.cmd.inst.asUInt(51,45)

  val is_8bits = convertOutType === CONVERT_E4M3
  val is_16bits = convertOutType === CONVERT_BF16

  // val convertMode_reg    = RegInit(0.U(2.W))
  // val convertInType_reg  = RegInit(0.U(7.W))
  // val convertOutType_reg = RegInit(0.U(7.W))

  // when(start) {
  //   convertMode_reg := convertMode
  //   convertInType_reg := convertInType
  //   convertOutType_reg := convertOutType
  // }

  val convert_fp32_e4m3 = Module(new ElewiseConvertOp(32, fpType, 8, e4m3Type, computeunit_len))
  convert_fp32_e4m3.io.input.bits.a       := qDataA.io.deq.bits.vec.asTypeOf(Vec(computeunit_len, fpType))
  convert_fp32_e4m3.io.input.bits.mode    := convertMode
  convert_fp32_e4m3.io.input.valid := (ops2DataValid && is_convert 
                                      && convertInType === CONVERT_FP32 && convertOutType === CONVERT_E4M3
  )

  val convert_fp32_bf16 = Module(new ElewiseConvertOp(32, fpType, 16, bf16Type, computeunit_len))
  convert_fp32_bf16.io.input.bits.a       := qDataA.io.deq.bits.vec.asTypeOf(Vec(computeunit_len, fpType))
  convert_fp32_bf16.io.input.bits.mode    := convertMode
  convert_fp32_bf16.io.input.valid := (ops2DataValid && is_convert 
                                      && convertInType === CONVERT_FP32 && convertOutType === CONVERT_BF16
  )


  //qDataA.io.deq.ready := ((ops_3 && ops3DataValid) || (ops_2 && ops2DataValid))
  qDataA.io.deq.ready := (ops_3 && ops3DataValid) || (ops_2 && ops2DataValid && ElewiseReduce.io.input.ready)
  qDataB.io.deq.ready := (ops_3 && ops3DataValid)
  
  // result write back
  arbResult.io.in(0).valid := ElewiseInt.io.output.valid
  arbResult.io.in(0).bits := ElewiseInt.io.output.bits.result.asTypeOf(Vec(computeunit_len, UInt(32.W)))
  ElewiseInt.io.output.ready := arbResult.io.in(0).ready

  arbResult.io.in(1).valid := ElewiseFp.io.output.valid
  arbResult.io.in(1).bits := ElewiseFp.io.output.bits.result.asTypeOf(Vec(computeunit_len, UInt(32.W)))
  ElewiseFp.io.output.ready := arbResult.io.in(1).ready

  arbResult.io.in(2).valid := VSFU.io.output.valid
  arbResult.io.in(2).bits := VSFU.io.output.bits.result
  VSFU.io.output.ready := arbResult.io.in(2).ready

  arbResult.io.in(3).valid := ElewiseReduce.io.output.valid && ElewiseReduce.io.output.bits.last_time
  arbResult.io.in(3).bits := VecInit(
    ElewiseReduce.io.output.bits.result +: 
    (0 until computeunit_len-1).map(_ => 0.U.asTypeOf(fpType))
  ).asTypeOf(Vec(computeunit_len, UInt(32.W)))
  ElewiseReduce.io.output.ready := arbResult.io.in(3).ready

  def convert_pack[T <: Data](in: Seq[T], inWidth: Int, outWidth: Int, computeunit_len: Int): Seq[UInt] = {
    require(outWidth >= inWidth, "outWidth must be >= inWidth")

    val packPerWord = outWidth / inWidth
    val inUInts: Seq[UInt] = in.map(_.asTypeOf(UInt(inWidth.W)))
    val groups: Seq[Seq[UInt]] = inUInts.grouped(packPerWord).toSeq

    val packed: Seq[UInt] = groups.map { g =>
      val bitsUsed = g.length * inWidth
      val padding  = outWidth - bitsUsed
      val concat   = Cat(g.reverse)
      if (padding > 0) Cat(0.U(padding.W), concat)
      else concat
    }

    packed ++ Seq.fill(computeunit_len - packed.length)(0.U(outWidth.W))
  }
  
  arbResult.io.in(4).valid := convert_fp32_e4m3.io.output.valid   
  arbResult.io.in(4).bits := convert_pack(convert_fp32_e4m3.io.output.bits.result, 8, 32, computeunit_len)
  convert_fp32_e4m3.io.output.ready := arbResult.io.in(4).ready
  
  arbResult.io.in(5).valid := convert_fp32_bf16.io.output.valid   
  arbResult.io.in(5).bits := convert_pack(convert_fp32_bf16.io.output.bits.result, 16, 32, computeunit_len)
  convert_fp32_bf16.io.output.ready := arbResult.io.in(5).ready


  // To do: ElewiseFpOp  reduce recip etc.

  // 地址计算
  val baseAddrA = Wire(UInt(32.W))
  val baseAddrB = Wire(UInt(32.W))
  val baseAddrOut = Wire(UInt(32.W))
  baseAddrA := nramAddrA
  baseAddrB := nramAddrB
  baseAddrOut := nramAddrOut

  // when(state === sLoad){printf("addra :%x addrb:%x addrc:%x\n",baseAddrA,  baseAddrB, baseAddrOut)}

  // val baseAddrAIsAcc = baseAddrA.asTypeOf(local_addr_t).is_acc_addr
  // val baseAddrBIsAcc = baseAddrB.asTypeOf(local_addr_t).is_acc_addr
  // val baseAddrOutIsAcc = baseAddrOut.asTypeOf(local_addr_t).is_acc_addr

  // assert(start && !baseAddrAIsAcc && !baseAddrOutIsAcc && ((ops_3 && !baseAddrBIsAcc) || ops_2) || !start)

  val op_w = log2Ceil(32 / 8)
  val a_dim0 = RegInit(0.U(32.W))
  val a_dim1 = RegInit(0.U(32.W))  
  val b_dim0 = RegInit(0.U(32.W))
  val b_dim1 = RegInit(0.U(32.W))
  val c_dim0 = RegInit(0.U(32.W))
  val c_dim1 = RegInit(0.U(32.W))
  val a_stride0 = RegInit(0.U(32.W))
  val a_stride1 = RegInit(0.U(32.W))
  val b_stride0 = RegInit(0.U(32.W))
  val b_stride1 = RegInit(0.U(32.W))
  val c_stride0 = RegInit(0.U(32.W))
  val c_stride1 = RegInit(0.U(32.W))

  val a_row = RegInit(0.U(16.W))
  val b_row = RegInit(0.U(16.W))
  val b_fill_row = RegInit(0.U(16.W))
  val out_row = RegInit(0.U(16.W))
  val a_offset = RegInit(0.U(16.W))
  val b_offset = RegInit(0.U(16.W))
  val b_fill_offset = RegInit(0.U(16.W))
  val out_offset = RegInit(0.U(16.W))
  val reduce_out_offset = RegInit(0.U(16.W))
  val reduce_enq_counter = RegInit(0.U(16.W))

  val is_reading_a_fired = Wire(Bool())
  val is_reading_b_fired = Wire(Bool())

  val first_read = RegInit(false.B)

  when(cmd_q.io.enq.fire){
    a_dim0 := tcfgrs1.TCFG_DIM0
    a_dim1 := tcfgrs1.TCFG_DIM1  
    b_dim0 := tcfgrs2.TCFG_DIM0
    b_dim1 := tcfgrs2.TCFG_DIM1
    c_dim0 := tcfgrs3.TCFG_DIM0
    c_dim1 := tcfgrs3.TCFG_DIM1
    a_stride0 := tcfgrs1.TCFG_STRIDE0
    a_stride1 := tcfgrs1.TCFG_STRIDE1
    b_stride0 := tcfgrs2.TCFG_STRIDE0
    b_stride1 := tcfgrs2.TCFG_STRIDE1
    c_stride0 := tcfgrs3.TCFG_STRIDE0
    c_stride1 := tcfgrs3.TCFG_STRIDE1

    first_read := true.B

    a_row := 0.U
    b_row := 0.U
    b_fill_row := 0.U
    out_row := 0.U
    a_offset := 0.U
    b_offset := 0.U
    b_fill_offset := 0.U
    out_offset := 0.U
    reduce_out_offset := 0.U
    reduce_enq_counter := 0.U

  }

  when(start){
    assert((
      !ops_3 || (a_stride0 === b_stride0 === c_stride0 || a_stride1 === b_stride1 === c_stride1) &&
        (a_dim0 === b_dim0 && a_dim1 === b_dim1 || b_dim0 === 1.U && cmd.cmd.inst.asUInt(45) || b_dim1 === 1.U && cmd.cmd.inst.asUInt(46))
    )) 
    assert(
      !ops_2  || is_convert || (a_stride0 === c_stride0 || a_stride1 === c_stride1)
    )
    // assert(!is_reduce || reduce_dim === 1.U)
  }
  val op_dim_is_0 = (a_stride0 === b_stride0 === c_stride0 === 1.U)

  broadcast := Mux(op_dim_is_0, cmd.cmd.inst.asUInt(48,45), Cat(cmd.cmd.inst.asUInt(45), cmd.cmd.inst.asUInt(46)))

  val a_op_max_dim = Wire(UInt(16.W))
  val b_op_max_dim = Wire(UInt(16.W))
  val out_op_max_dim = a_op_max_dim

  val a_max_row = Mux(op_dim_is_0, a_dim1, a_dim0)
  val b_max_row = Mux(op_dim_is_0, b_dim1, b_dim0)
  val out_max_row = a_max_row
  val a_max_offset = Wire(UInt(16.W))
  val b_max_offset = Wire(UInt(16.W))
  val out_max_offset = a_max_offset
  val a_offset_next = wrappingAdd(a_offset, 1.U, a_max_offset)
  val b_offset_next = wrappingAdd(b_offset, 1.U, b_max_offset)
  val b_fill_offset_next = wrappingAdd(b_fill_offset, 1.U, a_max_offset)
  val out_offset_next = wrappingAdd(out_offset, 1.U, out_max_offset)
  val a_row_next = floorAdd(a_row, 1.U, a_max_row)
  val b_row_next = wrappingAdd(b_row, 1.U, b_max_row)
  val b_fill_row_next = wrappingAdd(b_fill_row, 1.U, a_max_row)
  val out_row_next = wrappingAdd(out_row, 1.U, out_max_row)

  val reduce_out_offset_next = wrappingAdd(reduce_out_offset, 1.U, a_max_row)

  val reduce_enq_counter_next = wrappingAdd(reduce_enq_counter, 1.U, a_max_offset)

  reduce_first_time := reduce_enq_counter === 0.U
  reduce_last_time := reduce_enq_counter_next === 0.U

  a_op_max_dim := Mux(op_dim_is_0, a_dim0, a_dim1)
  b_op_max_dim := Mux(op_dim_is_0, b_dim0, b_dim1)

  a_max_offset := (a_op_max_dim-1.U)(15, log2Ceil(block_size)) + 1.U
  b_max_offset := (b_op_max_dim-1.U)(15, log2Ceil(block_size)) + 1.U
  last_block_size := Mux(a_op_max_dim(log2Ceil(computeunit_len)-1, 0) === 0.U, computeunit_len.U, a_op_max_dim(log2Ceil(computeunit_len)-1, 0))

  // 计算当前块的起始地址
  val currentAddrA = RegInit(0.U(nramAddrWidth.W))
  val currentAddrB = RegInit(0.U(nramAddrWidth.W))
  val currentAddrOut = RegInit(0.U(nramAddrWidth.W))
  val currentAddrReduceOut = RegInit(0.U(nramAddrWidth.W))
  val currentAddr8BitsOut = RegInit(0.U(nramAddrWidth.W))
  val currentAddr16BitsOut = RegInit(0.U(nramAddrWidth.W))
  val offset = RegInit(0.U(16.W))
  // dontTouch(currentAddrA)
  // dontTouch(currentAddrB)
  // dontTouch(currentAddrOut)
  // dontTouch(currentAddr8BitsOut)
  // dontTouch(currentAddr16BitsOut)
  val a_row_stride = Mux(op_dim_is_0, a_stride1, a_stride0)
  val b_row_stride = Mux(op_dim_is_0, b_stride1, b_stride0)
  val out_stride = Mux(op_dim_is_0, c_stride1, c_stride0)

  when(first_read){
    currentAddrA := (baseAddrA + (a_row * a_row_stride << op_w.U) + (a_offset * ((block_size << op_w).U)))
    currentAddrB := (baseAddrB + (b_row * b_row_stride << op_w.U) + (b_offset * ((block_size << op_w).U)))
    first_read := false.B
  }.elsewhen(start){
    when(is_reading_a_fired){
      currentAddrA := (baseAddrA + (a_row * a_row_stride << op_w.U) + (a_offset * ((block_size << op_w).U)))
    }
    when(is_reading_b_fired){
      currentAddrB := (baseAddrB + (b_row * b_row_stride << op_w.U) + (b_offset * ((block_size << op_w).U)))
    }
  }
  when((arbResult.io.out.fire || first_read) && start){
    offset := out_offset * block_size.U
    currentAddrOut := (baseAddrOut + (out_row * out_stride << op_w.U) + (out_offset * (block_size << op_w).U))
    currentAddrReduceOut := (baseAddrOut + (reduce_out_offset << op_w.U))
    currentAddr8BitsOut := (baseAddrOut + (out_row * out_stride) + (out_offset * block_size.U))
    currentAddr16BitsOut := (baseAddrOut + (out_row * out_stride << 1) + (out_offset * (block_size * 2).U))
  }
  // currentAddrA := (baseAddrA + (a_row * a_row_stride << op_w.U) + (a_offset * ((block_size << op_w).U))).asTypeOf(local_addr_t)
  // currentAddrB := (baseAddrB + (b_row * b_row_stride << op_w.U) + (b_offset * ((block_size << op_w).U))).asTypeOf(local_addr_t)


  // def same_bank(addr1: LocalAddr, addr2: LocalAddr, is_garbage1: Bool, is_garbage2: Bool): Bool = {
  //   val addr1_read_from_acc = addr1.is_acc_addr
  //   val addr2_read_from_acc = addr2.is_acc_addr

  //   val is_garbage = is_garbage1 || is_garbage2


  //   !is_garbage && ((addr1_read_from_acc && addr2_read_from_acc) ||
  //     (!addr1_read_from_acc && !addr2_read_from_acc && addr1.sp_bank() === addr2.sp_bank()))
  // }
  
  val ab_bank_conflict = true.B
  val uram_read_1_resp_is_b = RegInit(false.B)
  when(ops_3 && ab_bank_conflict && io.uram_read_1.resp.fire) {
    uram_read_1_resp_is_b := !uram_read_1_resp_is_b
  }.elsewhen(ops_2 || !ab_bank_conflict) {
    uram_read_1_resp_is_b := false.B
  }
  val a_valid = io.uram_read_1.resp.valid & !uram_read_1_resp_is_b && start //readValid(currentAddrA.sp_bank())
  val b_valid = ops_3 && Mux(ab_bank_conflict, io.uram_read_1.resp.valid && uram_read_1_resp_is_b, io.uram_read_2.resp.valid) && start//readValid(currentAddrB.sp_bank())
  val a_data = io.uram_read_1.resp.bits.data //readData(currentAddrA.sp_bank())
  val b_data = Mux(ab_bank_conflict, io.uram_read_1.resp.bits.data, io.uram_read_2.resp.bits.data) //readData(currentAddrB.sp_bank())

  // assert(ex_read_from_spad)

  val uram_read_1_req_is_b = RegInit(false.B)
  when(ops_3 && ab_bank_conflict && io.uram_read_1.req.fire) {
    uram_read_1_req_is_b := !uram_read_1_req_is_b
  }.elsewhen(ops_2 || !ab_bank_conflict) {
    uram_read_1_req_is_b := false.B
  }

  val is_reading_a_first = a_row === 0.U && a_offset === 0.U
  val is_reading_b_first = b_fill_row === 0.U && b_fill_offset === 0.U
  val is_reading_a_last = a_row_next === 0.U && a_offset_next === 0.U
  val is_reading_b_last = b_fill_row_next === 0.U && b_fill_offset_next === 0.U
  val is_reading_a_started = RegInit(false.B)
  val is_reading_b_started = RegInit(false.B)
  val is_reading_a_last_reg = RegInit(true.B)
  val is_reading_b_last_reg = RegInit(true.B)
  is_reading_a_fired := io.uram_read_1.req.fire && !uram_read_1_req_is_b
  is_reading_b_fired := io.uram_read_1.req.fire && uram_read_1_req_is_b || io.uram_read_2.req.fire
  when(first_read) {
    is_reading_a_last_reg := false.B
    when(ops_3){
      is_reading_b_last_reg := false.B
    }
  }.elsewhen(start) {
    when(is_reading_a_fired && a_row === 0.U && a_offset === 0.U){
      is_reading_a_last_reg := true.B
    }
    when(is_reading_b_fired && b_fill_row === 0.U && b_fill_offset === 0.U){
      is_reading_b_last_reg := true.B
    }
  }

  val read_a = start && !is_reading_a_last_reg && !uram_read_1_req_is_b
  val read_b = start && !is_reading_b_last_reg && !ops_2 && (ab_bank_conflict && uram_read_1_req_is_b || !ab_bank_conflict)
  io.uram_read_1.req.valid := Mux(ab_bank_conflict, read_a || read_b, read_a)
  io.uram_read_1.req.bits.addr := Mux(read_a || !ab_bank_conflict, currentAddrA, currentAddrB)
  io.uram_read_1.req.bits.fromDMA := false.B
  io.uram_read_1.resp.ready := Mux(uram_read_1_resp_is_b, qDataB.io.enq.ready, qDataA.io.enq.ready)
  io.uram_read_2.req.valid := Mux(ab_bank_conflict, false.B, read_b)
  io.uram_read_2.req.bits.addr := currentAddrB
  io.uram_read_2.req.bits.fromDMA := false.B
  io.uram_read_2.resp.ready := qDataB.io.enq.ready

  val is_writing_last = Mux(is_reduce, reduce_out_offset === 0.U, out_row === 0.U && out_offset === 0.U)
  val w_mask = (0 until computeunit_len).map(_.U + offset < Mux(is_reduce, 1.U, out_op_max_dim))
  io.uram_write.valid := arbResult.io.out.valid && start
  arbResult.io.out.ready := io.uram_write.ready
  // io.uram_write.addr := Mux(is_reduce, currentAddrReduceOut.asUInt, currentAddrOut.asUInt)
  io.uram_write.addr := MuxCase(
    currentAddrOut,
    Seq(
      is_reduce -> currentAddrReduceOut,
      (is_convert && is_8bits)   -> currentAddr8BitsOut,
      (is_convert && is_16bits)  -> currentAddr16BitsOut
    )
  )
  io.uram_write.data := arbResult.io.out.bits.asUInt
  io.uram_write.mask := w_mask.flatMap(b => Seq.fill(32 / (aligned_to * 8))(b))
  io.uram_write.acc := false.B

  val fp32_pos_inf = "h7f800000".U(32.W)
  val fp32_neg_inf = "hff800000".U(32.W)
  val padVal = Mux(opSel_reg === 2.U, 0.U(32.W), Mux(opSel_reg === 0.U, fp32_neg_inf, fp32_pos_inf))

  qDataA.io.enq.bits.vec := VecInit.tabulate(computeunit_len) { i =>
    val elem = a_data((i + 1) * 32 - 1, i * 32)
    Mux(is_reduce && reduce_last_time && (i.U >= last_block_size), padVal, elem)
  }
  qDataA.io.enq.bits.reduce_first_time := reduce_first_time
  qDataA.io.enq.bits.reduce_last_time := reduce_last_time
  qDataA.io.enq.valid := a_valid

  qDataB.io.enq.bits := VecInit.tabulate(computeunit_len) { i => b_data((i + 1) * 32 - 1, i * 32).asTypeOf(UInt(32.W))}
  qDataB.io.enq.valid := b_valid

  when((is_reading_a_fired || first_read) && start) {
    a_offset := a_offset_next
    when(a_offset_next ===  0.U) {
      a_row := a_row_next
    }
  }

  when(qDataA.io.enq.fire && start) {
    reduce_enq_counter := reduce_enq_counter_next
  }
 

  when((is_reading_b_fired || first_read && ops_3) && start) {
    b_offset := b_offset_next
    b_fill_offset := b_fill_offset_next
    when(b_fill_offset_next === 0.U) {
      b_row := b_row_next
      b_fill_row := b_fill_row_next
    }
  }

  when((arbResult.io.out.fire || first_read) && start) {
    when(is_reduce){
      reduce_out_offset := reduce_out_offset_next
    }.otherwise{
      out_offset := out_offset_next
      when(out_offset_next === 0.U) {
        out_row := out_row_next
      }
    }
  }
  
  // 输出状态
  io.busy := start && !(is_writing_last && arbResult.io.out.fire)
  io.done := start && !io.busy
}


```


#### Short summary: 

empty definition using pc, found symbol in pc: 