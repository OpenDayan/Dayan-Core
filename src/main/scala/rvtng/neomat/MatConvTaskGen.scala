package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

/*
* 说明：
*  - 该模块用于卷积任务拆分，内部例化两个 Conv Producer Task Gen，一个 Consumer Task Gen 及一个 Conv PostProcessTaskGen
*  - 卷积映射为 M->Wo, N->Co, K->Hk*Wk*CiBytes；Ho 由本模块作为外层子任务循环。
*  - 当前首版要求 Wo/Co/CiBytes 分别按 cM/cN/cK 对齐，且不支持 padding zero-fill。
* */
class MatConvTaskGen(params: MatParams) extends Module {
  val io = IO(new Bundle{
    val cmdIn = Flipped(Decoupled(new MatTask(params)))
    val ldA = Decoupled(new DPALoadCmd(params, params.log2CountATile))
    val ldAAck = Input(Bool())
    val ldARelease = Input(Bool())
    val ldB = Decoupled(new DPALoadCmd(params, params.log2CountBTile))
    val ldBAck = Input(Bool())
    val ldBRelease = Input(Bool())
    val calc = Decoupled(new DPACalcCmd(params))
    val pp = Decoupled(new DPAPostProcessCmd(params))
    val ppAlloc = Flipped(Decoupled(Bool()))
  })

  private val ciByteDec1 = Wire(UInt(params.MNKWidth.W))
  ciByteDec1 := Mux(
    io.cmdIn.bits.bf16,
    Cat(io.cmdIn.bits.taskCi, 1.U(1.W))(params.MNKWidth - 1, 0),
    io.cmdIn.bits.taskCi
  )
  val convKByteCount = (io.cmdIn.bits.taskHk + 1.U) * (io.cmdIn.bits.taskWk + 1.U) * (ciByteDec1 + 1.U)
  val convKByteDec1 = convKByteCount(params.MNKWidth - 1, 0) - 1.U

  // 任务拆分逻辑：卷积映射 M->Wo, N->Co, K->Hk*Wk*CiBytes
  val tileM = io.cmdIn.bits.taskWo >> params.log2CM
  val tileN = io.cmdIn.bits.taskCo >> params.log2CN
  val tileK = convKByteDec1 >> params.log2CK
  val inputWoStride = io.cmdIn.bits.wiStride * io.cmdIn.bits.strideW
  val inputHoStride = io.cmdIn.bits.hiStride * io.cmdIn.bits.strideH
  val strideDilationW = Mux(
    io.cmdIn.bits.strideDilationW === 0.U,
    io.cmdIn.bits.wiStride * io.cmdIn.bits.dilationW,
    io.cmdIn.bits.strideDilationW
  )
  val strideDilationH = Mux(
    io.cmdIn.bits.strideDilationH === 0.U,
    io.cmdIn.bits.hiStride * io.cmdIn.bits.dilationH,
    io.cmdIn.bits.strideDilationH
  )

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)
  val cmdTileM = RegEnable(tileM, io.cmdIn.fire)
  val cmdTileN = RegEnable(tileN, io.cmdIn.fire)
  val cmdTileK = RegEnable(tileK, io.cmdIn.fire)
  val cmdCiByteDec1 = RegEnable(ciByteDec1, io.cmdIn.fire)
  val cmdInputWoStride = RegEnable(inputWoStride, io.cmdIn.fire)
  val cmdInputHoStride = RegEnable(inputHoStride, io.cmdIn.fire)
  val cmdStrideDilationW = RegEnable(strideDilationW, io.cmdIn.fire)
  val cmdStrideDilationH = RegEnable(strideDilationH, io.cmdIn.fire)

  // 卷积外层循环：Ho；Hk/Wk/CiBytes 作为 Consumer 的 K 维完成累加
  val curHo = Reg(UInt(params.MNKWidth.W))

  val lastHo = curHo === cmd.taskHo
  val lastSubTask = lastHo
  val issueSubTask = Wire(Bool())

  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  when(io.cmdIn.fire) {
    curHo := 0.U
  }.elsewhen(issueSubTask) {
    when(lastHo) {
      curHo := 0.U
    }.otherwise {
      curHo := curHo + 1.U
    }
  }

  // Producer 例化
  val pA = Module(new MatConvProducerTaskGen(params, params.log2CountATile, params.sM, params.sK, isKernel = false))
  val pB = Module(new MatConvProducerTaskGen(params, params.log2CountBTile, params.sN, params.sK, isKernel = true))

  // Consumer 例化
  val c = Module(new MatConsumerTaskGen(params))

  // Post-Processor 例化
  val pp = Module(new MatConvPostProcessTaskGen(params))

  // 连线
  io.ldA <> Queue(pA.io.cmdOut, 2)
  pA.io.ackIn := io.ldAAck
  pA.io.releaseIn := io.ldARelease
  io.ldB <> Queue(pB.io.cmdOut, 2)
  pB.io.ackIn := io.ldBAck
  pB.io.releaseIn := io.ldBRelease

  c.io.aAck <> pA.io.ackOut
  c.io.bAck <> pB.io.ackOut
  io.calc <> Queue(c.io.cmdOut, 2)
  c.io.ppAlloc <> io.ppAlloc

  io.pp <> Queue(pp.io.cmdOut, 2)

  val aCmd = Wire(DecoupledIO(new MatConvProducerTaskGenCmd(params)))
  aCmd.bits.addr := cmd.aBaseAddr
  aCmd.bits.swizzle := cmd.ASwizzleMBS
  aCmd.bits.major := cmdTileM
  aCmd.bits.minor := cmdTileK
  aCmd.bits.repeat := 0.U
  aCmd.bits.ho := curHo
  aCmd.bits.ciByteDec1 := cmdCiByteDec1
  aCmd.bits.taskWk := cmd.taskWk
  aCmd.bits.wiStride := cmd.wiStride
  aCmd.bits.hiStride := cmd.hiStride
  aCmd.bits.wkStride := cmd.wkStride
  aCmd.bits.hkStride := cmd.hkStride
  aCmd.bits.coStride := cmd.coStride
  aCmd.bits.inputWoStride := cmdInputWoStride
  aCmd.bits.inputHoStride := cmdInputHoStride
  aCmd.bits.strideDilationW := cmdStrideDilationW
  aCmd.bits.strideDilationH := cmdStrideDilationH
  aCmd.bits.padL := cmd.padL
  aCmd.bits.padU := cmd.padU

  val bCmd = Wire(DecoupledIO(new MatConvProducerTaskGenCmd(params)))
  bCmd.bits.addr := cmd.bBaseAddr
  bCmd.bits.swizzle := cmd.BSwizzleMBS
  bCmd.bits.major := cmdTileN
  bCmd.bits.minor := cmdTileK
  bCmd.bits.repeat := cmdTileM >> params.log2SM
  bCmd.bits.ho := curHo
  bCmd.bits.ciByteDec1 := cmdCiByteDec1
  bCmd.bits.taskWk := cmd.taskWk
  bCmd.bits.wiStride := cmd.wiStride
  bCmd.bits.hiStride := cmd.hiStride
  bCmd.bits.wkStride := cmd.wkStride
  bCmd.bits.hkStride := cmd.hkStride
  bCmd.bits.coStride := cmd.coStride
  bCmd.bits.inputWoStride := cmdInputWoStride
  bCmd.bits.inputHoStride := cmdInputHoStride
  bCmd.bits.strideDilationW := cmdStrideDilationW
  bCmd.bits.strideDilationH := cmdStrideDilationH
  bCmd.bits.padL := cmd.padL
  bCmd.bits.padU := cmd.padU

  val cCmd = Wire(DecoupledIO(new MatConsumerTaskGenCmd(params)))
  cCmd.bits.mTile := cmdTileM
  cCmd.bits.nTile := cmdTileN
  cCmd.bits.kTile := cmdTileK
  cCmd.bits.bf16 := cmd.bf16

  val ppCmd = Wire(DecoupledIO(new MatConvPostProcessTaskGenCmd(params)))
  ppCmd.bits.addr := cmd.cBaseAddr
  ppCmd.bits.woStride := cmd.woStride
  ppCmd.bits.hoStride := cmd.hoStride
  ppCmd.bits.swizzle := cmd.CSwizzleMBS
  ppCmd.bits.woTile := cmdTileM
  ppCmd.bits.ho := curHo
  ppCmd.bits.coTile := cmdTileN
  ppCmd.bits.repeat := cmdTileK >> params.log2SK
  ppCmd.bits.accumulate := cmd.accumulate
  ppCmd.bits.lastTask := lastSubTask

  // 命令连线
  pA.io.cmdIn <> Queue(aCmd, 2)
  pB.io.cmdIn <> Queue(bCmd, 2)
  c.io.cmdIn <> Queue(cCmd, 2)
  pp.io.cmdIn <> Queue(ppCmd, 2)

  issueSubTask := valid & aCmd.ready & bCmd.ready & cCmd.ready & ppCmd.ready
  finish := issueSubTask & lastSubTask

  aCmd.valid := issueSubTask
  bCmd.valid := issueSubTask
  cCmd.valid := issueSubTask
  ppCmd.valid := issueSubTask

}

object MatConvTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatConvTaskGen(MatParams(
      sM = 2, sN = 2, sK = 16
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatConvTaskGen"
    )
  )
}
