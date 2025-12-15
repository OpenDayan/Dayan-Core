error id: file://<WORKSPACE>/src/main/scala/rvtng/vpu/TcsrUnit.scala:
file://<WORKSPACE>/src/main/scala/rvtng/vpu/TcsrUnit.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb

found definition using fallback; symbol TcsrUnit
offset: 291
uri: file://<WORKSPACE>/src/main/scala/rvtng/vpu/TcsrUnit.scala
text:
```scala
package main.scala.rvtng.vpu

import chisel3.Reg
import chisel3.util.Decoupled
import chisel3.when
import rvtng.vpu.GemminiCmd
import rvtng.vpu.GemminiISA.tcsr_xchg_nmask_cmd
import rvtng.vpu.tcsrcontent
import chisel3.Bundle
import chisel3.Flipped
import rvtng.vpu.VpuParams

class TcsrUnit@@(TcsrNum: Int = 64, TcsrWidth: Int = 32, params: VpuParams) extends Module{
    import params._
    val io = IO(new Bundle{
        val cmdin = Flipped(Decoupled(new GemminiCmd(reservation_station_entries)))
    })
    
    val TCSR = Reg(Vec(64, UInt(32.W)))
    val tcsr_xchg_nmask = raw_cmd.bits.cmd.inst.funct === tcsr_xchg_nmask_cmd 
    val tcsr_cmd = tcsr_xchg_nmask && raw_cmd.valid
    val tcsr_w_id = raw_cmd.bits.cmd.inst.asUInt(41,32)
    val tcsr_rs1 = raw_cmd.bits.cmd.inst.rs1
    val tcsr_rs3 = raw_cmd.bits.cmd.inst.rs3
    val tcsr_rd = raw_cmd.bits.cmd.inst.rd

    when(raw_cmd.fire && tcsr_cmd){
      val data = (raw_cmd.bits.cmd.rs1 & ~raw_cmd.bits.cmd.rs3) | (TCSR(tcsr_w_id).asUInt & raw_cmd.bits.cmd.rs3)
      TCSR(tcsr_w_id) := (raw_cmd.bits.cmd.rs1 & ~raw_cmd.bits.cmd.rs3) | (TCSR(tcsr_w_id).asUInt & raw_cmd.bits.cmd.rs3)
    }

    //inst with tcsr info
    val tcsr_filter_cmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))
    tcsr_filter_cmd.valid := raw_cmd.valid && !tcsr_cmd
    tcsr_filter_cmd.bits.cmd := raw_cmd.bits.cmd
    tcsr_filter_cmd.bits.from_conv_fsm := raw_cmd.bits.from_conv_fsm
    tcsr_filter_cmd.bits.from_matmul_fsm := raw_cmd.bits.from_matmul_fsm
    tcsr_filter_cmd.bits.rob_id := raw_cmd.bits.rob_id
    
    raw_cmd.ready := tcsr_filter_cmd.ready && !tcsr_cmd || (tcsr_cmd && instResp.ready)
    val tcsr_reads = Wire(Vec(3, new tcsrcontent(32)))
    val tcsr_r_id = Wire(Vec(3, UInt(10.W)))

    tcsr_r_id(0) := raw_cmd.bits.cmd.inst.tcfgrs1 * 8.U
    tcsr_r_id(1) := raw_cmd.bits.cmd.inst.tcfgrs2 * 8.U
    tcsr_r_id(2) := raw_cmd.bits.cmd.inst.tcfgrs3 * 8.U

    for (i <- 0 until 3){
      tcsr_reads(i) := VecInit((0 until 8).map(j =>TCSR(tcsr_r_id(i) + j.U))).asTypeOf(new tcsrcontent(32))// Cat(TCSR(tcsr_r_id(i)), TCSR(tcsr_r_id(i) + 1.U), TCSR(tcsr_r_id(i) + 2.U), TCSR(tcsr_r_id(i) + 3.U),TCSR(tcsr_r_id(i) + 4.U), TCSR(tcsr_r_id(i) + 5.U), TCSR(tcsr_r_id(i) + 6.U), TCSR(tcsr_r_id(i) + 7.U)).asTypeOf(new tcsrcontent(32))
    }
    tcsr_filter_cmd.bits.tcfgrs := tcsr_reads
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 