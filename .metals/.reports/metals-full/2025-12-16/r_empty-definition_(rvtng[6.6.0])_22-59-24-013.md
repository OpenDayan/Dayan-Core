error id: file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUTop.scala:read
file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUTop.scala
empty definition using pc, found symbol in pc: read
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/VPU.io.srams.read.
	 -chisel3/util/VPU.io.srams.read.
	 -GemminiISA.VPU.io.srams.read.
	 -Util.VPU.io.srams.read.
	 -rvtng/top/VPU.io.srams.read.
	 -rvtng/uram/VPU.io.srams.read.
	 -params/VPU.io.srams.read.
	 -VPU.io.srams.read.
	 -scala/Predef.VPU.io.srams.read.
offset: 4302
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
import rvtng.top._
import rvtng.uram._
import rvtng.mat.MatScoreboard

class VPUTop[T <: Data](params: VpuParams)(implicit ev: Arithmetic[T]) extends Module{
    import params._

    val instReq = IO(Flipped(Decoupled(new TensorInst())))
    //TODO: instResp？ sbcomp？
    val instResp = IO(Decoupled(new TensorResp()))
    val sbComp  = IO(Decoupled(new ScoreboardComp()))

    private val uramPort = (params.laneWidth * 32 / 2) / 64
    private val uramPortPerChannel = params.uramPortPerChannel
    private val uramChannel = uramPort / uramPortPerChannel

    // URAM 读口
    val uramRReq  = IO(Vec(uramPort, Decoupled(
      new AdvURAMReq(params.uramAddrBits, 0, 64))))
    val uramRResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
      new AdvURAMResp(params.uramAddrBits,  0, 64))))))

    // URAM 写口
    val uramWReq = IO(Vec(uramPort, Decoupled(
      new AdvURAMReq(params.uramAddrBits, 0, 64))))
    val uramWData = IO(Output(Vec(uramPort, UInt(64.W))))
    val uramWResp = IO(Flipped(Vec(uramChannel, Decoupled(Vec(uramPortPerChannel,
        new AdvURAMWResp(params.uramAddrBits,  0, 64))))))

    private val VPU = Module(new VPUwrapper(params))

    private val TU = Module(new TcsrUnit(64, 32, params))

    private val instQueue = Module(new Queue(new GemminiCmd(reservation_station_entries), 32))

    instQueue.io.enq <> TU.io.cmdout

    val raw_cmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))
    raw_cmd.valid := instReq.valid
    raw_cmd.bits.cmd.inst := instReq.bits.instWord.asTypeOf(new RoCCInstruction)
    raw_cmd.bits.cmd.rs1 := instReq.bits.rs1Data
    raw_cmd.bits.cmd.rs2 := instReq.bits.rs2Data
    raw_cmd.bits.cmd.rs3 := instReq.bits.rdData
    raw_cmd.bits.rob_id := DontCare
    raw_cmd.bits.tcfgrs := DontCare
    raw_cmd.bits.from_conv_fsm := false.B
    raw_cmd.bits.from_matmul_fsm := false.B
    instReq.ready := raw_cmd.ready

    TU.io.cmdin <> raw_cmd

    //inst resp source: tcsr, sb
    private val sb = Module(new VpuScoreboard(params))

    private val decodeCompReq   = instReq.bits.instWord(31, 27) === 0b00001.U

    val respArb = Module(new Arbiter(instResp.bits.cloneType, 2)).io
    respArb.in(0) <> TU.io.instResp
    respArb.in(1) <> sb.instResp

    sb.instReq.bits := instReq.bits
    sb.instReq.valid := instReq.valid & decodeCompReq
    sb.instIssue := instQueue.io.enq.fire
    sb.instCommit := VPU.io.done
    sb.sbComp <> sbComp

    instResp <> respArb.out

    VPU.io.start := instQueue.io.deq.valid
    VPU.io.cmd := instQueue.io.deq.bits
    instQueue.io.deq.ready := VPU.io.done

    class WriteReq extends Bundle{
      val writeio = Vec(uramPort, (new AdvURAMReq(params.uramAddrBits, 0, 64)))
      val mask = Vec((row_width / (aligned_to * 8)) max 1, Bool())
      val last = Bool()
    }

    val WriteReqQueue = Module(new Queue(new WriteReq, 16))
    val WriteRespQueue = Module(new Queue(new WriteReq, 16))


    //TODO: 不同resp端口返回带有的decouple用处
    //TODO: sb没用到

    VPU.io.srams.read.resp.valid := uramRResp(0).valid
    VPU.io.srams.read.resp.bits.data := uramRResp(0).bits(0).data
    VPU.io.srams.read.resp.bits.fromDMA := false.B
    uramRResp(0).ready := VPU.io.srams.read.resp.ready

    VPU.io.srams.write.ready := uramWResp(0).valid

    for( i <- 1 until uramChannel){
      uramRResp(i).ready := true.B
    }

    for( i <- 0 until uramChannel){
      uramWResp(i).ready := true.B
    }

    WriteReqQueue.io.enq.bits.mask := VPU.io.srams.write.mask
    WriteReqQueue.io.enq.bits.last := VPU.io.done
    WriteReqQueue.io.enq.valid := VPU.io.srams.write.valid
    WriteRespQueue.io.enq.valid := WriteReqQueue.io.deq.fire
    WriteRespQueue.io.enq.bits := WriteReqQueue.io.deq.bits


    WriteReqQueue.io.deq.ready := VecInit((0 until computeunit_len*acc_width/(8*8)).map(i => uramWReq(i).ready)).reduce(_||_)
    

    for( i <- 0 until uramPort){
      if(i < computeunit_len*acc_width/(8*8)){
        uramRReq(i).valid := VPU.io.srams.@@read.req.valid
        uramRReq(i).bits.address := VPU.io.srams.read.req.bits.addr + (i * 8).U
        uramRReq(i).bits.id := WriteReqQueue.io.deq.bits.last
        VPU.io.srams.read.req.ready := uramRReq(i).ready

        WriteReqQueue.io.enq.bits.writeio(i).address := VPU.io.srams.write.addr + (i * 8).U
        WriteReqQueue.io.enq.bits.writeio(i).id := 0.U

        uramWReq(i).valid := Mux(WriteReqQueue.io.deq.bits.mask(i * 8), WriteReqQueue.io.deq.valid, false.B)
        uramWReq(i).bits.address := WriteReqQueue.io.deq.bits.writeio(i).address
        uramWReq(i).bits.id := WriteReqQueue.io.deq.bits.writeio(i).id

        uramWData(i) := VPU.io.srams.write.data(i*64 + 63, i*64)
      }else{
        uramRReq(i) <> DontCare
        uramWReq(i) <> DontCare
        uramWData(i) <> DontCare
        uramRReq(i).valid := false.B 
        uramWReq(i).valid := false.B
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
  private val vpu = Module(new VPUTop(params))

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val sbComp  = IO(Decoupled(new ScoreboardComp()))

  vpu.instReq <> instReq
  vpu.instResp <> instResp
  vpu.sbComp <> sbComp
  vpu.uramRReq <> uram.r0req_i(0)
  vpu.uramRResp <> uram.r0resp_o(0)
  vpu.uramWReq <> uram.wreq_i(0)
  vpu.uramWResp <> uram.wresp_o(0)
  uram.wdata_i(0) := vpu.uramWData
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
    ),
    "VPUTestTop"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/VPUTestTop"
    )
  )
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: read