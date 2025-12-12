package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import rvtng.fpmath.DPArray.{FP8BF16FusedDPArray, U8m2n2k1U4m2n2k2Mul}
import rvtng.fpmath.signedFP.SignedFP
import rvtng.utils.genPipe

// 内部浮点数
object MatInnerSingedFP {
  def apply(params: MatParams): SignedFP = new SignedFP(params.wE, params.wF)
}

// 内部总线抽象
class MatInnerTileReadReq(params: MatParams, regIdWidth: Int, posWidth: Int) extends Bundle {
  val regId = UInt(regIdWidth.W)
  val position = UInt(posWidth.W) // 一个 Tile Register Fetch 请求，可能会切分为多个 transaction 去执行，此时用 position 区别。
  val addr = UInt(params.addrWidth.W)
  val stride = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val last = Bool() // 最后一个请求
}

class MatInnerTileChannelReadResp(params: MatParams, regIdWidth: Int, posWidth: Int) extends Bundle {
  val valid = Bool()
  val regId = UInt(regIdWidth.W)
  val position = UInt(posWidth.W) // 一个 Tile Register Fetch 请求，可能会切分为多个 transaction 去执行，此时用 position 区别。
  val data = Vec(params.channelWidth, UInt(8.W))
}

class MatInnerTileWriteReq(params: MatParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val last = Bool()
  val data = Vec(params.channelWidth, UInt(8.W))
}

class MatInnerTileChannelWriteResp extends Bundle {
  val valid = Bool()
  val last = Bool()
}

// 该模块作为执行后段，接收来自前端发来的 load / calc 指令，完成矩阵乘法运算并将结果流式输出给 PE 做后处理。
class MatExec(params: MatParams) extends Module {
  val aTileSize = params.cM * params.cK
  val bTileSize = params.cN * params.cK
  val aTransactionCount = (aTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  val log2ATransactionCount = log2Ceil(aTransactionCount)
  val bTransactionCount = (bTileSize + (params.busChannel * params.channelWidth) - 1) / (params.busChannel * params.channelWidth)
  val log2BTransactionCount = log2Ceil(bTransactionCount)
  val io = IO(new Bundle {
    val ldA = Flipped(Decoupled(new DPALoadCmd(params, params.log2CountATile)))
    val ldAAck = Output(Bool())
    val ldARelease = Output(Bool())
    val ldB = Flipped(Decoupled(new DPALoadCmd(params, params.log2CountBTile)))
    val ldBAck = Output(Bool())
    val ldBRelease = Output(Bool())
    val calc = Flipped(Decoupled(new DPACalcCmd(params)))

    // 结果流式输出
    val result = Decoupled(Vec(params.cM * params.cN, MatInnerSingedFP(params)))

    // 简化总线接口
    val busAReq = Decoupled(new MatInnerTileReadReq(params, params.log2CountATile, log2ATransactionCount))
    val busBReq = Decoupled(new MatInnerTileReadReq(params, params.log2CountBTile, log2BTransactionCount))
    val busAResp = Input(Vec(params.busChannel, new MatInnerTileChannelReadResp(params, params.log2CountATile, log2ATransactionCount)))
    val busBResp = Input(Vec(params.busChannel, new MatInnerTileChannelReadResp(params, params.log2CountBTile, log2BTransactionCount)))
    val busAAck = Input(Bool())
    val busBAck = Input(Bool())
  })

  // BUS 2B View
  val busDataA_2B = VecInit(io.busAResp.map(_.data)).asTypeOf(Vec(params.busChannel, Vec(params.channelWidth / 2, UInt(16.W))))
  val busDataB_2B = VecInit(io.busBResp.map(_.data)).asTypeOf(Vec(params.busChannel, Vec(params.channelWidth / 2, UInt(16.W))))
  dontTouch(busDataA_2B)
  dontTouch(busDataB_2B)

  // TODO: Use Systolic Array like structure here.
  val dpa = Module(new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
    M = params.cM, N = params.cN, K = params.cK, sK = params.cK, // Note: sK is deperated for now.
    wE = params.wE, wF = params.wF,
    debugFP32Output = true, enableStickBit = false, wMul = 17
  ))

  // Tile Register
  val aTile = Module(new MatTileRegisters(params, params.log2CountATile, aTileSize))
  val bTile = Module(new MatTileRegisters(params, params.log2CountBTile, bTileSize))

  // Tile View
  val aTileFP8 = aTile.io.data.asTypeOf(Vec(params.cM, Vec(params.cK, UInt(8.W))))
  val bTileFP8 = bTile.io.data.asTypeOf(Vec(params.cN, Vec(params.cK, UInt(8.W))))
  val aTileBF16 = aTile.io.data.asTypeOf(Vec(params.cM, Vec(params.cK / 2, UInt(16.W))))
  val bTileBF16 = bTile.io.data.asTypeOf(Vec(params.cN, Vec(params.cK / 2, UInt(16.W))))
  dontTouch(aTileFP8)
  dontTouch(bTileFP8)
  dontTouch(aTileBF16)
  dontTouch(bTileBF16)

  // Tile Command Interface
  aTile.io.cmdIn <> io.ldA
  io.ldAAck := io.busAAck
  io.busAReq <> aTile.io.busReq
  aTile.io.busResp := io.busAResp
  bTile.io.cmdIn <> io.ldB
  io.ldBAck := io.busBAck
  io.busBReq <> bTile.io.busReq
  bTile.io.busResp := io.busBResp

  // 时序模块，硬流水处理 r0 及 cout 的输出问题
  val delayedVld = genPipe(io.calc.fire, dpa.r0Latency + 3)
  val delayedCmd = genPipe(io.calc.bits, dpa.r0Latency + 3)

  // Calc Handshake
  val cRegPipe = Reg(Vec(3, new Bundle {
    val valid = Bool()
    val regId = UInt(params.log2CountCTile.W)
  }))
  cRegPipe.foldLeft({
    val initV = Wire(cRegPipe(0).cloneType)
    initV.valid := io.calc.fire
    initV.regId := io.calc.bits.regC
    initV
  })((l, r) => {
    r := l
    r
  })

  // Note: prevent C data raw hazard.
  io.calc.ready := !VecInit(cRegPipe.map(iter => {
    iter.valid & (iter.regId === io.calc.bits.regC)
  })).reduceTree(_ | _)

  // Early Release of Tile
  io.ldARelease := io.calc.fire & io.calc.bits.releaseA
  io.ldBRelease := io.calc.fire & io.calc.bits.releaseB

  // TODO: Connect me
  dpa.valid := RegNext(io.calc.fire)
  dpa.bf16 := RegNext(io.calc.bits.bf16)
  aTile.io.re := io.calc.fire
  bTile.io.re := io.calc.fire
  aTile.io.regId := io.calc.bits.regA
  bTile.io.regId := io.calc.bits.regB
  dpa.a := aTileFP8
  dpa.b := bTileFP8
  dpa.a_scale := 0.U.asTypeOf(dpa.a_scale)
  dpa.b_scale := 0.U.asTypeOf(dpa.b_scale)

  // 内建的小型 cBuffer
  val cTile = Reg(Vec(params.sM * params.sN, Vec(params.cM * params.cN, MatInnerSingedFP(params))))
  dpa.r0_cin_exp_in := RegNext(Mux(!delayedVld(dpa.r0Latency) | delayedCmd(dpa.r0Latency).clear,
    VecInit.tabulate(params.cM)(m => VecInit.tabulate(params.cN)(n => (BigInt(1) << (params.wE-1)).S(params.wE.W))),
    VecInit(cTile(delayedCmd(dpa.r0Latency).regC).map(_.exp)).asTypeOf(dpa.r0_cin_exp_in)))
  dpa.r0_cin_frac_in := RegNext(Mux(!delayedVld(dpa.r0Latency) | delayedCmd(dpa.r0Latency).clear,
    VecInit.tabulate(params.cM)(m => VecInit.tabulate(params.cN)(n => 0.S(params.wF.W))),
    VecInit(cTile(delayedCmd(dpa.r0Latency).regC).map(_.frac)).asTypeOf(dpa.r0_cin_frac_in)))
  val dpaOut = Wire(Vec(params.cM * params.cN, MatInnerSingedFP(params)))
  val dpaOutExp = dpa.cout_exp.asTypeOf(Vec(params.cM * params.cN, SInt(params.wE.W)))
  val dpaOutFrac = dpa.cout_frac.asTypeOf(Vec(params.cM * params.cN, SInt(params.wF.W)))
  // 补全 zip 展开
  dpaOut
    .zip(dpaOutExp.zip(dpaOutFrac))
    .foreach { case (out, (exp, frac)) =>
      out.exp := exp
      out.frac := frac
    }
  when(delayedVld(dpa.r0Latency + 3)) {
    cTile(delayedCmd(dpa.r0Latency + 3).regC) := dpaOut
  }

  io.result.valid := delayedVld(dpa.r0Latency + 3) & delayedCmd(dpa.r0Latency + 3).writeback
  io.result.bits := dpaOut

  chisel3.assert(!(io.result.valid & !io.result.ready))
}

object MatExec extends App {
  ChiselStage.emitSystemVerilog(
    new MatExec(MatParams(
      sM = 2, sN = 2, sK = 16
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatExec"
    )
  )
}
