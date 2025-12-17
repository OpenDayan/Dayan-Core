package rvtng.vpu

import chisel3._
import chisel3.util._
import rvtng.vpu.GemminiISA._
import chisel3.Reg
import chisel3.util.Decoupled
import chisel3.when
import rvtng.vpu.GemminiCmd
import rvtng.vpu.GemminiISA.tcsr_xchg_nmask_cmd
import rvtng.vpu.tcsrcontent
import chisel3.Bundle
import chisel3.Flipped
import rvtng.vpu.VpuParams
import chisel3.Data
import rvtng.top.TensorResp


//tcsr模块，输入原始指令，输出加入tcsr info之后的指令
class TcsrUnit(TcsrNum: Int = 64, TcsrWidth: Int = 32, params: VpuParams) extends Module{
    import params._
    val io = IO(new Bundle{
        val cmdin = Flipped(Decoupled(new GemminiCmd(reservation_station_entries)))
        val cmdout = Decoupled(new GemminiCmd(reservation_station_entries))
        val instResp = Decoupled(new TensorResp())
    })
    
    val TCSR = Reg(Vec(64, UInt(32.W)))
    val tcsr_xchg_nmask = io.cmdin.bits.cmd.inst.funct === tcsr_xchg_nmask_cmd 
    val tcsr_cmd = tcsr_xchg_nmask && io.cmdin.valid
    val tcsr_w_id = io.cmdin.bits.cmd.inst.asUInt(41,32)
    val tcsr_rs1 = io.cmdin.bits.cmd.inst.rs1
    val tcsr_rs3 = io.cmdin.bits.cmd.inst.rs3
    val tcsr_rd = io.cmdin.bits.cmd.inst.rd

    when(io.cmdin.fire && tcsr_cmd){
      val data = (io.cmdin.bits.cmd.rs1 & ~io.cmdin.bits.cmd.rs3) | (TCSR(tcsr_w_id(5, 0)).asUInt & io.cmdin.bits.cmd.rs3)
      TCSR(tcsr_w_id(5, 0)) := (io.cmdin.bits.cmd.rs1 & ~io.cmdin.bits.cmd.rs3) | (TCSR(tcsr_w_id(5, 0)).asUInt & io.cmdin.bits.cmd.rs3)
    }

    //inst with tcsr info
    val tcsr_filter_cmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))
    tcsr_filter_cmd.valid := io.cmdin.valid && !tcsr_cmd
    tcsr_filter_cmd.bits.cmd := io.cmdin.bits.cmd
    tcsr_filter_cmd.bits.from_conv_fsm := io.cmdin.bits.from_conv_fsm
    tcsr_filter_cmd.bits.from_matmul_fsm := io.cmdin.bits.from_matmul_fsm
    tcsr_filter_cmd.bits.rob_id := io.cmdin.bits.rob_id
    
    io.cmdin.ready := tcsr_filter_cmd.ready && !tcsr_cmd || (tcsr_cmd && io.instResp.ready)
    val tcsr_reads = Wire(Vec(3, new tcsrcontent(32)))
    val tcsr_r_id = Wire(Vec(3, UInt(10.W)))

    tcsr_r_id(0) := io.cmdin.bits.cmd.inst.tcfgrs1 * 8.U
    tcsr_r_id(1) := io.cmdin.bits.cmd.inst.tcfgrs2 * 8.U
    tcsr_r_id(2) := io.cmdin.bits.cmd.inst.tcfgrs3 * 8.U

    for (i <- 0 until 3){
      tcsr_reads(i) := VecInit((0 until 8).map(j => TCSR((tcsr_r_id(i) + j.U)(5, 0)))).asTypeOf(new tcsrcontent(32))
    }
    tcsr_filter_cmd.bits.tcfgrs := tcsr_reads

    io.cmdout <> tcsr_filter_cmd

    io.instResp.valid := tcsr_cmd
    io.instResp.bits.data := TCSR(tcsr_w_id(5, 0))
    io.instResp.bits.rd := tcsr_rd
}