package rvtng.neomat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class MatConvPostProcessTaskGenCmd(params: MatParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val woStride = UInt(params.addrWidth.W)
  val hoStride = UInt(params.addrWidth.W)
  val swizzle = UInt(24.W)
  val woTile = UInt(params.MNKWidth.W) // Wo tile count dec 1
  val ho = UInt(params.MNKWidth.W)
  val coTile = UInt(params.MNKWidth.W) // Co tile count dec 1
  val repeat = UInt(params.MNKWidth.W)
  val accumulate = Bool()
  val lastTask = Bool()
}

class MatConvPostProcessTaskGen(params: MatParams) extends Module {
  val io = IO(new Bundle {
    val cmdIn = Flipped(Decoupled(new MatConvPostProcessTaskGenCmd(params)))
    val cmdOut = Decoupled(new DPAPostProcessCmd(params))
  })

  val valid = RegInit(0.B)
  val finish = Wire(Bool())
  io.cmdIn.ready := finish | !valid
  val cmd = RegEnable(io.cmdIn.bits, io.cmdIn.fire)

  val curRepeat = Reg(UInt(params.MNKWidth.W))
  val curBaseWo = Reg(UInt(params.MNKWidth.W))
  val curBaseCo = Reg(UInt(params.MNKWidth.W))
  val curSubWo = Reg(UInt(params.log2SM.W))
  val curSubCo = Reg(UInt(params.log2SN.W))

  val lastSubWo = curSubWo === (params.sM - 1).U
  val lastSubCo = curSubCo === (params.sN - 1).U

  val curWo = curBaseWo | curSubWo
  val curCo = curBaseCo | curSubCo
  dontTouch(cmd.ho)
  dontTouch(curWo)
  dontTouch(curCo)

  val lastWo = curWo === cmd.woTile
  val lastCo = curCo === cmd.coTile
  val lastRepeat = curRepeat === cmd.repeat

  finish := lastWo & lastCo & lastRepeat & io.cmdOut.fire

  when(io.cmdIn.fire) {
    valid := 1.B
  }.elsewhen(finish) {
    valid := 0.B
  }

  when(io.cmdIn.fire) {
    curRepeat := 0.U
    curBaseWo := 0.U
    curBaseCo := 0.U
    curSubWo := 0.U
    curSubCo := 0.U
  }.elsewhen(io.cmdOut.fire) {
    when(lastSubCo | lastCo) {
      curSubCo := 0.U
      when(lastSubWo | lastWo) {
        curSubWo := 0.U
        when(lastCo) {
          curBaseCo := 0.U
          when(lastWo) {
            curBaseWo := 0.U
            curRepeat := curRepeat + 1.U
          }.otherwise {
            curBaseWo := curBaseWo + params.sM.U
          }
        }.otherwise {
          curBaseCo := curBaseCo + params.sN.U
        }
      }.otherwise {
        curSubWo := curSubWo + 1.U
      }
    }.otherwise {
      curSubCo := curSubCo + 1.U
    }
  }

  io.cmdOut.valid := valid
  io.cmdOut.bits.clear := !cmd.accumulate & (curRepeat === 0.U)
  io.cmdOut.bits.swizzle := cmd.swizzle
  io.cmdOut.bits.stride := cmd.woStride
  // O_addr = O_base + ho * HoStride + wo * WoStride + co * sizeof(FP32)
  val outputHOffset = cmd.ho * cmd.hoStride
  val outputWOffset = curWo * params.cM.U * cmd.woStride
  val outputCoOffset = (4 * params.cN).U * curCo
  io.cmdOut.bits.addr := cmd.addr + outputHOffset + outputWOffset + outputCoOffset
  io.cmdOut.bits.last := cmd.lastTask & lastWo & lastCo & lastRepeat
}

object MatConvPostProcessTaskGen extends App {
  ChiselStage.emitSystemVerilog(
    new MatConvPostProcessTaskGen(MatParams(
      sM = 2, sN = 2, sK = 16
    )),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/MatConvPostProcessTaskGen"
    )
  )
}
