error id: file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUTop.scala:chisel3/Data#asUInt().
file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUTop.scala
empty definition using pc, found symbol in pc: 
found definition using semanticdb; symbol chisel3/Data#
empty definition using fallback
non-local guesses:

offset: 4323
uri: file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUTop.scala
text:
```scala
package rvtng.vpu

import chisel3._
import chisel3.util._
import GemminiISA._

import Util._
import rvtng.top.ScoreboardComp
import rvtng.top.TensorInst
import rvtng.top.TensorResp
import rvtng.uram.AdvURAMReq
import rvtng.uram.AdvURAMResp
import rvtng.uram.AdvURAMWResp
import _root_.circt.stage.ChiselStage
import rvtng.uram.Adv2R1WURAM
import rvtng.vec.VecParams
import rvtng.vec.VecTop
import rvtng.top._
import rvtng.uram._

class VPUTop[T <: Data, U <: Data, V <: Data](params: VpuParams)(implicit ev: Arithmetic[T]) extends Module{
    import params._

    val instReq = IO(Flipped(Decoupled(new TensorInst())))
    val instResp = IO(Decoupled(new TensorResp()))
    val sbComp  = IO(Decoupled(new ScoreboardComp()))

    private val uramPort = (params.laneWidth * 32 / 2) / 64
    private val uramPortPerChannel = params.uramPortPerChannel
    private val uramChannel = uramPort / uramPortPerChannel

    // URAM 读口
    val uramRReq  = IO(Vec(uramPort, Decoupled(
      new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
    val uramRResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
      new AdvURAMResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

    // URAM 写口
    val uramWReq = IO(Vec(uramPort, Decoupled(
      new AdvURAMReq(params.uramAddrBits, params.sourceBits - 1, 64))))
    val uramWData = IO(Output(Vec(uramPort, UInt(64.W))))
    val uramWResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
        new AdvURAMWResp(params.uramAddrBits,  params.sourceBits - 1, 64))))))

    private val VPU = Module(new VPUwrapper(params))
    //TODO: 指令目前是32位的，并且没有rs3位
    val raw_cmd_q = Module(new Queue(new GemminiCmd(reservation_station_entries), entries = 2))
    raw_cmd_q.io.enq.valid := instReq.valid
    instReq.ready := raw_cmd_q.io.enq.ready
    raw_cmd_q.io.enq.bits.cmd.inst := instReq.bits.instWord.asTypeOf(new RoCCInstruction)
    raw_cmd_q.io.enq.bits.cmd.rs1 := instReq.bits.rs1Data
    raw_cmd_q.io.enq.bits.cmd.rs2 := instReq.bits.rs2Data
    raw_cmd_q.io.enq.bits.cmd.rs3 := DontCare
    raw_cmd_q.io.enq.bits.rob_id := DontCare
    raw_cmd_q.io.enq.bits.tcfgrs := DontCare
    raw_cmd_q.io.enq.bits.from_conv_fsm := false.B
    raw_cmd_q.io.enq.bits.from_matmul_fsm := false.B

    val raw_cmd = raw_cmd_q.io.deq

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
      tcsr_reads(i) := Cat(TCSR(tcsr_r_id(i)), TCSR(tcsr_r_id(i) + 1.U), TCSR(tcsr_r_id(i) + 2.U), TCSR(tcsr_r_id(i) + 3.U),TCSR(tcsr_r_id(i) + 4.U), TCSR(tcsr_r_id(i) + 5.U), TCSR(tcsr_r_id(i) + 6.U), TCSR(tcsr_r_id(i) + 7.U)).asTypeOf(new tcsrcontent(32))
    }
    tcsr_filter_cmd.bits.tcfgrs := tcsr_reads

    VPU.io.start := tcsr_filter_cmd.valid
    VPU.io.cmd := tcsr_filter_cmd.bits
    tcsr_filter_cmd.ready := VPU.io.done

    //TODO: 不同resp端口返回带有的decouple用处
    //TODO: sb没用到

    VPU.io.srams.read.resp.valid := uramRResp(0).valid
    VPU.io.srams.read.resp.bits := uramRResp(0).bits.s@@

    VPU.io.srams.write.ready := uramWResp(0).valid


    for( i <- 0 until uramPort){
      if(i < computeunit_len*acc_width/(8*8)){
        uramRReq(i).valid := VPU.io.srams.read.req.valid
        uramRReq(i).bits.address := VPU.io.srams.read.req.bits.addr + (i * 8).U

        uramWReq(i).valid := Mux(VPU.io.srams.write.mask(i * 8), VPU.io.srams.write.valid, false.B)
        uramWReq(i).bits.address := VPU.io.srams.write.addr

        uramWData(i) := VPU.io.srams.write.data(i*64 + 63, i*64)
      }else{
        uramRReq(i) <> DontCare
        uramWReq(i) <> DontCare
      }
      
    }
}

class VPUTestTop[T <: Data](params: VpuParams, name: String = "VecTestTop")(implicit ev: Arithmetic[T]) extends Module {

  override def desiredName: String = name
  private val uramPort = (params.laneWidth * 32 / 2) / 64
  private val uramPortPerChannel = params.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // URAM Instance
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 1, R1Port = 1, W0Port = 1, GroupCount = params.uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = params.sourceBits, dataWidth = 64
  ))

  // DMA Instance
  private val vec = Module(new VPUTop(params))

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  vec.instReq <> instReq
  vec.instResp <> instResp
  vec.sbComp <> sbComp
  vec.uramRReq <> uram.r0req_i(0)
  vec.uramRResp <> uram.r0resp_o(0)
  vec.uramWReq <> uram.wreq_i(0)
  vec.uramWResp <> uram.wresp_o(0)
  uram.wdata_i(0) := vec.uramWData
  uram.r1req_i(0).map(u => {
    u.bits := DontCare
    u.valid := 0.B
  })
  uram.r1resp_o(0).map(u => {
    u.ready := 1.B
  })
}

object VPUTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new VPUTestTop[Float](VpuParams(
      sp_bank_entries = 1024,
    ),
    "VecTinyTestTop"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/VecTinyTestTop"
    )
  )
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: 