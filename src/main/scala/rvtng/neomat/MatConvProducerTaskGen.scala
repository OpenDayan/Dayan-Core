package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatConvProducerTaskGenCmd(params: MatParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)

  // 复用 MatProducerTaskGen 的 major/minor/repeat 循环壳：
  //   Input  Producer: major = Wo tile, minor = Hk * Wk * CiBytes tile, repeat = 0
  //   Kernel Producer: major = Co tile, minor = Hk * Wk * CiBytes tile, repeat = Wo tile group
  val major = UInt(params.MNKWidth.W) // Output W / Co tile count dec 1
  val repeat = UInt(params.MNKWidth.W)
  val minor = UInt(params.MNKWidth.W) // Hk * Wk * CiBytes tile count dec 1

  // Ho 由 MatConvTaskGen 外层循环传入，Hk/Wk/CiBytes 由本模块在 K 维解码。
  val ho = UInt(params.MNKWidth.W)
  val ciByteDec1 = UInt(params.MNKWidth.W)
  val taskWk = UInt(params.MNKWidth.W)

  // ISA 中的原始 stride，以及在 MatConvTaskGen 中预先算好的组合 stride。
  val wiStride = UInt(params.addrWidth.W)
  val hiStride = UInt(params.addrWidth.W)
  val wkStride = UInt(params.addrWidth.W)
  val hkStride = UInt(params.addrWidth.W)
  val coStride = UInt(params.addrWidth.W)
  val inputWoStride = UInt(params.addrWidth.W)
  val inputHoStride = UInt(params.addrWidth.W)
  val strideDilationW = UInt(params.addrWidth.W)
  val strideDilationH = UInt(params.addrWidth.W)
  val padL = UInt(params.MNKWidth.W)
  val padU = UInt(params.MNKWidth.W)
}

class MatConvProducerTaskGen(
  params: MatParams,
  log2CountXTile: Int,
  sMajor: Int,
  sMinor: Int,
  isKernel: Boolean
) extends Module {
  /*
   * 说明：
   *  - 该模块是卷积专用 Producer，控制流、Tile Register 分配/回收、Ack 顺序都沿用 MatProducerTaskGen。
   *  - 与 GEMM Producer 的主要区别仅在地址生成：
   *      Input  地址对应 I[ho * StrideH + kh * DilationH - PadU, wo * StrideW + kw * DilationW - PadL, ci]
   *      Kernel 地址对应 K[kh, kw, ci, co]
   *  - 当前首版要求 CiBytes 按 cK 对齐，因为 load path 还没有 tail mask 和 padding zero-fill。
   */
  val io = IO(new Bundle {
    val cmdIn = Flipped(Decoupled(new MatConvProducerTaskGenCmd(params)))
    val cmdOut = Decoupled(new DPALoadCmd(params, log2CountXTile))
    val ackIn = Input(Bool())
    val ackOut = Decoupled(new MatProducerAck(params, log2CountXTile))
    val releaseIn = Input(Bool())
  })

  // 当前正在处理任务的信号
  val valid = RegInit(0.B)
  // 下一拍开始可以接受新请求
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)

  // TileRegister 分配及回收计数
  val trFreeCount = RegInit((1 << log2CountXTile).U)
  val trRdyCount = RegInit(0.U((log2CountXTile + 1).W))

  // 指针
  val trAllocHead = RegInit(0.U(log2CountXTile.W))
  when(io.cmdOut.fire) {
    trAllocHead := trAllocHead + 1.U
  }
  io.cmdOut.valid := valid & (trFreeCount =/= 0.U)
  io.cmdOut.bits.regId := trAllocHead

  val trAckHead = RegInit(0.U(log2CountXTile.W))
  when(io.ackOut.fire) {
    trAckHead := trAckHead + 1.U
  }
  io.ackOut.valid := trRdyCount =/= 0.U
  io.ackOut.bits.regId := trAckHead

  // 分配回收计数更新
  trFreeCount := trFreeCount + io.releaseIn.asUInt - io.cmdOut.fire.asUInt
  trRdyCount := trRdyCount + io.ackIn.asUInt - io.ackOut.fire.asUInt
  // 主要循环状态机
  val curRepeat = Reg(UInt(params.MNKWidth.W))
  val curBaseMajor = Reg(UInt(params.MNKWidth.W))
  val curBaseMinor = Reg(UInt(params.MNKWidth.W))
  val curSubMajor = Reg(UInt(sMajor.W))
  val curSubMinor = Reg(UInt(sMinor.W))
  val curBaseKh = Reg(UInt(params.MNKWidth.W))
  val curBaseKw = Reg(UInt(params.MNKWidth.W))
  val curBaseCiByte = Reg(UInt(params.MNKWidth.W))
  val curKh = Reg(UInt(params.MNKWidth.W))
  val curKw = Reg(UInt(params.MNKWidth.W))
  val curCiByte = Reg(UInt(params.MNKWidth.W))
  // 子分块边界计算
  val lastSubMajor = curSubMajor === (sMajor - 1).U
  val lastSubMinor = curSubMinor === (sMinor - 1).U
  // 计算真实的 Major Minor 下标
  val curMajor = curSubMajor | curBaseMajor
  val curMinor = curSubMinor | curBaseMinor
  // Debug 接口调试用
  dontTouch(curMajor)
  dontTouch(curMinor)
  // 真实边界计算
  val lastMajor = curMajor === cmd.major
  val lastRepeat = curRepeat === cmd.repeat
  val lastMinor = curMinor === cmd.minor

  // 结束情况
  finish := lastMajor & lastMinor & lastRepeat & io.cmdOut.fire

  val lastCiByte = cmd.ciByteDec1 + 1.U - params.cK.U
  def nextKIndex(kh: UInt, kw: UInt, ciByte: UInt): (UInt, UInt, UInt) = {
    val nextKh = Wire(UInt(params.MNKWidth.W))
    val nextKw = Wire(UInt(params.MNKWidth.W))
    val nextCiByte = Wire(UInt(params.MNKWidth.W))
    nextKh := kh
    nextKw := kw
    nextCiByte := ciByte + params.cK.U
    when(ciByte === lastCiByte) {
      nextCiByte := 0.U
      when(kw === cmd.taskWk) {
        nextKw := 0.U
        nextKh := kh + 1.U
      }.otherwise {
        nextKw := kw + 1.U
      }
    }
    (nextKh, nextKw, nextCiByte)
  }

  val nextCurK = nextKIndex(curKh, curKw, curCiByte)
  val nextBaseKh = Wire(Vec(sMinor + 1, UInt(params.MNKWidth.W)))
  val nextBaseKw = Wire(Vec(sMinor + 1, UInt(params.MNKWidth.W)))
  val nextBaseCiByte = Wire(Vec(sMinor + 1, UInt(params.MNKWidth.W)))
  nextBaseKh(0) := curBaseKh
  nextBaseKw(0) := curBaseKw
  nextBaseCiByte(0) := curBaseCiByte
  for(i <- 0 until sMinor) {
    val next = nextKIndex(nextBaseKh(i), nextBaseKw(i), nextBaseCiByte(i))
    nextBaseKh(i + 1) := next._1
    nextBaseKw(i + 1) := next._2
    nextBaseCiByte(i + 1) := next._3
  }

  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  // 输入命令发射时，填入初始值
  when(io.cmdIn.fire) {
    curRepeat := 0.U
    curBaseMajor := 0.U
    curBaseMinor := 0.U
    curSubMajor := 0.U
    curSubMinor := 0.U
    curBaseKh := 0.U
    curBaseKw := 0.U
    curBaseCiByte := 0.U
    curKh := 0.U
    curKw := 0.U
    curCiByte := 0.U
  }.elsewhen(io.cmdOut.fire) {
    when(lastMajor | lastSubMajor) {
      curSubMajor := 0.U
      when(lastMinor | lastSubMinor) {
        curSubMinor := 0.U
        when(lastMajor) {
          curBaseMajor := 0.U
          when(lastRepeat) {
            curRepeat := 0.U
            curBaseMinor := curBaseMinor + sMinor.U
            curBaseKh := nextBaseKh(sMinor)
            curBaseKw := nextBaseKw(sMinor)
            curBaseCiByte := nextBaseCiByte(sMinor)
            curKh := nextBaseKh(sMinor)
            curKw := nextBaseKw(sMinor)
            curCiByte := nextBaseCiByte(sMinor)
          }.otherwise {
            curRepeat := curRepeat + 1.U
            curKh := curBaseKh
            curKw := curBaseKw
            curCiByte := curBaseCiByte
          }
        }.otherwise {
          curBaseMajor := curBaseMajor + sMajor.U
          curKh := curBaseKh
          curKw := curBaseKw
          curCiByte := curBaseCiByte
        }
      }.otherwise {
        curSubMinor := curSubMinor + 1.U
        curKh := nextCurK._1
        curKw := nextCurK._2
        curCiByte := nextCurK._3
      }
    }.otherwise {
      curSubMajor := curSubMajor + 1.U
    }
  }

  // Kernel:
  //   K_addr = K_base + kh * HkStride + kw * WkStride + ciByte + coTile * cN * CoStride
  //   DPALoadCmd.stride = CoStride，使 tile 内 cN 个 Co 通道沿 Co 维展开。
  val kernelHOffset = curKh * cmd.hkStride
  val kernelWOffset = curKw * cmd.wkStride
  val kernelCoOffset = curMajor * params.cN.U * cmd.coStride
  val kernelAddr = cmd.addr + kernelHOffset + kernelWOffset + curCiByte + kernelCoOffset

  // Input:
  //   ih = ho * StrideH + kh * DilationH - PadU
  //   iw = wo * StrideW + kw * DilationW - PadL
  //   I_addr = I_base + ih * HiStride + iw * WiStride + ciByte
  val inputHOffset = cmd.ho * cmd.inputHoStride + curKh * cmd.strideDilationH - cmd.padU * cmd.hiStride
  val inputWOffset = curMajor * params.cM.U * cmd.inputWoStride + curKw * cmd.strideDilationW - cmd.padL * cmd.wiStride
  val inputAddr = cmd.addr + inputHOffset + inputWOffset + curCiByte

  io.cmdOut.bits.swizzle := cmd.swizzle
  if(isKernel) {
    io.cmdOut.bits.stride := cmd.coStride
    io.cmdOut.bits.addr := kernelAddr
  } else {
    // Input tile 的 M 维对应连续 cM 个 Wo 位置，因此 tile 内行间 stride 是 inputWoStride。
    io.cmdOut.bits.stride := cmd.inputWoStride
    io.cmdOut.bits.addr := inputAddr
  }
}

object MatConvProducerTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatConvProducerTaskGen(MatParams(
      sM = 2, sN = 2, sK = 16
    ), 5, 2, 16, isKernel = false),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatConvProducerTaskGen"
    )
  )
}
