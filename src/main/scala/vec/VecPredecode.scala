package vec

import chisel3._
import chisel3.util.Decoupled
import chisel3.experimental.BundleLiterals._
import _root_.circt.stage.ChiselStage

class VecPredecode(decoder: (UInt) => VecInstDecT) extends Module {
  val newblock = IO(Input(Bool()))
  val inst_i = IO(Flipped(Decoupled(new InstT)))
  val inst_o = IO(Decoupled(new VecInstT))

  // Type Define
  val TimeStampT = SInt(VecConfiguration._VEC_RF_ENTRY_LOG2.W + VecConfiguration._VEC_STALL_CYC_LOG2.W);
  val SbPtrT = UInt(VecConfiguration._VEC_RF_ENTRY_LOG2.W);

  // Inner Scoreboard
  val sb = Reg(Vec(VecConfiguration._VEC_RF_ENTRY, TimeStampT))
  val cur_ptr = RegInit(0.U(SbPtrT.getWidth.W))
  val cur_ts  = RegInit(0.S(TimeStampT.getWidth.W))

  // Inner Combinational Signals
  val inst_dec = decoder(inst_i.bits.inst);

  val cur_r1ptr = cur_ptr + inst_dec.r1 + 1.U;
  val cur_r2ptr = cur_ptr + inst_dec.r2 + 1.U;
  val cur_wts  = cur_ts + inst_dec.latency.zext + 1.S;
  val cur_r1ts = sb(cur_r1ptr);
  val cur_r2ts = sb(cur_r2ptr);

  val cur_r1diff = cur_r1ts - cur_ts;
  val cur_r2diff = cur_r2ts - cur_ts;

  val cur_val_valid = inst_dec.r1v | inst_dec.r2v;
  val cur_val_longer = Mux((cur_r1diff > cur_r2diff) & inst_dec.r1v, cur_r1diff, cur_r2diff);
  val cur_stall_cyc = Mux((cur_val_longer > 0.S) & cur_val_valid, cur_val_longer, 0.S);

  inst_i.ready := inst_o.ready;

  val valid = RegInit(0.B);
  val inst = Reg(new VecInstT);
  inst_o.valid := valid;
  inst_o.bits := inst;

  when (inst_i.fire) {
    sb(cur_ptr) := cur_wts;
    cur_ptr := cur_ptr + 1.U;
    cur_ts := cur_ts + cur_stall_cyc + 1.S;
    inst.inst := inst_i.bits.inst;
    inst.rd := inst_i.bits.rd;
    inst.block := inst_i.bits.block;
    inst.rs1 := inst_i.bits.rs1;
    inst.rs2 := inst_i.bits.rs2;
    inst.stall_cyc := cur_stall_cyc(VecConfiguration._VEC_STALL_CYC_LOG2 - 1, 0).asUInt;
    valid := inst_i.valid;
  }
  when (newblock) {
    cur_ptr := 0.U;
  }
}

object VecPredecode extends App {
  ChiselStage.emitSystemVerilogFile(
    new VecPredecode( (in:UInt) => {
      val tmp = Wire(new VecInstDecT())
      tmp.r1 := in(9, 5)
      tmp.r2 := in(4, 0)
      tmp.latency := in(14, 10)
      tmp.r1v := in(15)
      tmp.r2v := in(16)
      tmp
    }),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-preserve-aggregate=vec")
  )
}
