package mat

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import fpmath.{FP8BF16FusedDPArray, U8m2n2k1U4m2n2k2Mul}
import uram._
import utils._

object DPArrayStorageCmd extends ChiselEnum {
  val loadA, loadB, loadC = Value
}

// Note: `storageRatio` Marks the biggest storageRatio in design.
// StorageRatio form:
// A: 16 >= MK 8 2
// B: 16 >= NK 4 2
// C: 32 >= MN 8 4
class MatCalcCommand(storageRatio: Int) extends Bundle {
  // Compute Mode
  val bf16 = Bool()
  val aBank = Bool()
  val bBank = Bool()
  val cBank = Bool()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SM = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SN = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SK = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pM = UInt(log2Ceil(storageRatio).W) // pM <= sM
  val pN = UInt(log2Ceil(storageRatio).W) // pN <= sN
  val pK = UInt(log2Ceil(storageRatio).W) // pK <= sK
}


class MatLoadStoreCommand(storageRatio: Int, mnkWidth: Int, addressWidth: Int, cMNK: Int) extends Bundle {
  // Compute Mode
  val bank = Bool()
  val lastLoadAB = Bool()  // Need Completion, Last Load Command In A/B
  val waitLoadC  = Bool()  // Completion need to wait C Completion or not
  val ctrl = DPArrayStorageCmd()
  // Storage Shape
  // 0 -> 1, 1 -> 2, 2 -> 4, 3 -> 8, 4 -> 16, 5 -> 32
  val log2SDimMajor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  val log2SDimMinor = UInt(log2Ceil(1+log2Ceil(storageRatio)).W)
  // Problem Shape
  val pMajor = UInt(log2Ceil(storageRatio * cMNK).W) // pM / pN
  val pMinor = UInt(log2Ceil(storageRatio * cMNK).W) //      pK

  // Address Information
  val baseAddr     = UInt(addressWidth.W)
  val majorStride  = UInt(addressWidth.W) // in Bytes
  val baseDimMajor = UInt(mnkWidth.W)
  val baseDimMinor = UInt(mnkWidth.W)
}

class DPArrayTop(
 storageRatio: Int, busWidth: Int, mnkWidth: Int, addressWidth: Int,
 cM: Int, cN: Int, cK: Int, sK: Int,
 wE: Int = 9, wF: Int = 25) extends Module {
  val cMNK = Array(cM, cN, cK).max
  val maxTileSize = Array(cM * cK, cN * cK, cM * cN * 4).max
  val maxBeat = maxTileSize / busWidth
  val ports = busWidth / 8

  // Load/Store Cmd Interface
  val ldCmd = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))
  val stCmd = IO(Flipped(Decoupled(new MatLoadStoreCommand(storageRatio, mnkWidth, addressWidth, cMNK))))

  // Calculate  Cmd Interface
  val calcCmd = IO(Flipped(Decoupled(new MatCalcCommand(storageRatio))))

  // Completion Resp Interface
  val ldComp = IO(Decoupled(Bool()))
  val crComp = IO(Decoupled(Bool()))
  val cwComp = IO(Decoupled(Bool()))
  val stComp = IO(Decoupled(Bool()))

  // URAM Interface
  val rreq0  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val rresp0 = IO(Flipped(Vec(ports, new AdvURAMResp(addressWidth,   0, 64))))
  val rreq1  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val rresp1 = IO(Flipped(Vec(ports, new AdvURAMResp(addressWidth,   0, 64))))
  val wreq  = IO(Vec(ports, Decoupled(new AdvURAMReq(addressWidth, 0, 64))))
  val wdata = IO(Output(Vec(ports, UInt(64.W))))

  // Non-Blocking calculating array
  val calcCore = Module(new FP8BF16FusedDPArray((tM, tN, tK) => new U8m2n2k1U4m2n2k2Mul(tM, tN, tK),
    M = cM, N = cN, K = cK, sK = sK,
    debugFP32Output = true
  ))

  // Tensor Buffer
  val aBuffer = Module(new DPArrayBufferAB(busWidth, 32, cM, cK))
  val bBuffer = Module(new DPArrayBufferAB(busWidth, 32, cN, cK))
  val cBuffer = Module(new DPArrayBufferC (busWidth, 32, cM, cN, wE, wF))

  val abBankSft = log2Ceil(storageRatio / 2)
  val cBankSft  = log2Ceil(storageRatio)
  // DPArray Calculating Task Spilt
  val calcTaskSpilt = Module(new DPArrayCalcTaskSpilt(storageRatio, calcCore.r0Latency,
    abBankSft, abBankSft, cBankSft))

  calcTaskSpilt.cmdIn :<>= calcCmd
  crComp :<>= calcTaskSpilt.crComp
  cwComp :<>= calcTaskSpilt.cwComp

  val calcCMatAddrPipe = pipe.genPipe(calcTaskSpilt.cmdOut.bits.cMatAddr, calcCore.r0Latency + 2)
  val calcCMatValidPipe = pipe.genPipe(calcTaskSpilt.cmdOut.valid, calcCore.r0Latency + 2)

  calcTaskSpilt.cmdOut.ready := 1.B
  aBuffer.rAddr := calcTaskSpilt.cmdOut.bits.aMatAddr
  bBuffer.rAddr := calcTaskSpilt.cmdOut.bits.bMatAddr
  cBuffer.cRAddr := calcCMatAddrPipe(calcCore.r0Latency)
  cBuffer.cRe := calcCMatValidPipe(calcCore.r0Latency)

  calcCore.valid := RegNext(calcTaskSpilt.cmdOut.valid)
  calcCore.bf16 := RegNext(calcTaskSpilt.cmdOut.bits.bf16)
  calcCore.a := aBuffer.rData
  calcCore.b := bBuffer.rData
  // TODO: Add A and B Scale buffer
  calcCore.a_scale := 0.U.asTypeOf(calcCore.a_scale)
  calcCore.b_scale := 0.U.asTypeOf(calcCore.b_scale)
  calcCore.r0_cin_exp_in := VecInit.tabulate(cM, cN)((m, n) => {
    cBuffer.cRData(m)(n)(wF + wE - 1, wF).asSInt
  })

  calcCore.r0_cin_frac_in := VecInit.tabulate(cM, cN)((m, n) => {
    cBuffer.cRData(m)(n)(wF - 1, 0).asSInt
  })

  cBuffer.cWData := VecInit.tabulate(cM, cN)((m, n) => {
    Cat(calcCore.cout_exp(m)(n), calcCore.cout_frac(m)(n))
  })
  cBuffer.cWAddr := calcCMatAddrPipe(calcCore.r0Latency + 2)
  cBuffer.cWe := calcCMatValidPipe(calcCore.r0Latency + 2)

  // Connecting wPort / rPort of all buffer to uram
  val flattenUramRData0 = VecInit.tabulate(ports)(i => rresp0(i).data).asTypeOf(Vec(busWidth, UInt(8.W)))
  val flattenUramRData1 = VecInit.tabulate(ports)(i => rresp1(i).data).asTypeOf(Vec(busWidth, UInt(8.W)))
  aBuffer.wData    := flattenUramRData0
  bBuffer.wData    := flattenUramRData0
  cBuffer.extWData := flattenUramRData1
  wdata := cBuffer.extRData.asTypeOf(wdata)

  // Load Store Task Spilt Unit

  // A / B Load Task Spilt Unit
  val abLoadTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, abTask = true, cTask = false, storeTask = false, abBankSft
  ))
  // C Load Task Spilt Unit
  val cLoadTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, abTask = false, cTask = true, storeTask = false, cBankSft
  ))
  // C Store Task Spilt Unit
  val cStoreTaskSpilter = Module(new DPArrayLoadStoreTaskSpilt(
    storageRatio = storageRatio, busWidth = busWidth, mnkWidth = mnkWidth, addressWidth = addressWidth,
    cMNK = cMNK, cM = cM, cN = cN, cK = cK, abTask = false, cTask = true, storeTask = true, cBankSft
  ))
  abLoadTaskSpilter.cmdIn.valid := ldCmd.valid & ldCmd.bits.ctrl =/= DPArrayStorageCmd.loadC
  cLoadTaskSpilter.cmdIn.valid := ldCmd.valid & ldCmd.bits.ctrl === DPArrayStorageCmd.loadC
  ldCmd.ready := Mux(ldCmd.bits.ctrl === DPArrayStorageCmd.loadC,
    cLoadTaskSpilter.cmdIn.ready, abLoadTaskSpilter.cmdIn.ready)
  abLoadTaskSpilter.cmdIn.bits  := ldCmd.bits
  cLoadTaskSpilter.cmdIn.bits   := ldCmd.bits
  cStoreTaskSpilter.cmdIn :<>= stCmd
  val cStoreURAMReq = Queue(cStoreTaskSpilter.cmdURAM)

  // Address Calculation for URAM RReq-0
  // Per Line Used Ports:
  val portsPerLine0 = cK / 8
  (0 until ports).foreach(i => {
    // Get Mapped MN - K
    val fixedMN = i / portsPerLine0
    val fixedK = 8 * (i % portsPerLine0)
    val mn = fixedMN.U + abLoadTaskSpilter.cmdURAM.bits.baseDimMajor // 对应行起始
    val k =  fixedK.U + abLoadTaskSpilter.cmdURAM.bits.baseDimMinor  // 对应列起始
    // 对应偏移
    val addr = mn * abLoadTaskSpilter.cmdURAM.bits.majorStride + k + abLoadTaskSpilter.cmdURAM.bits.baseUramAddr
    // Swizzling 逻辑
    // 开始连线
    rreq0(i).bits.id := 0.U
    rreq0(i).bits.address := addr
    rreq0(i).valid := abLoadTaskSpilter.cmdURAM.valid &
      (abLoadTaskSpilter.cmdURAM.bits.pMajor >= fixedMN.U) &
      (abLoadTaskSpilter.cmdURAM.bits.pMinor >= fixedK.U)
  })
  // TODO: Check synchronized URAM
  abLoadTaskSpilter.cmdURAM.ready := rreq0(0).ready
  abLoadTaskSpilter.cmdBuffer.ready := rresp0(0).valid
  aBuffer.wAddr := abLoadTaskSpilter.cmdBuffer.bits.bufferAddr
  aBuffer.wBeat := abLoadTaskSpilter.cmdBuffer.bits.beat
  aBuffer.we := abLoadTaskSpilter.cmdBuffer.valid & !abLoadTaskSpilter.cmdBuffer.bits.isBLoad & rresp0(0).valid
  bBuffer.wAddr := abLoadTaskSpilter.cmdBuffer.bits.bufferAddr
  bBuffer.wBeat := abLoadTaskSpilter.cmdBuffer.bits.beat
  bBuffer.we := abLoadTaskSpilter.cmdBuffer.valid &  abLoadTaskSpilter.cmdBuffer.bits.isBLoad & rresp0(0).valid

  val portsPerLine1 = cN * 4 / 8
  (0 until ports).foreach(i => {
    // Get Mapped MN - K
    val fixedM = i / portsPerLine1
    val fixedN = 2 * (i % portsPerLine1)
    val m = fixedM.U + cLoadTaskSpilter.cmdURAM.bits.baseDimMajor // 对应行起始
    val n = fixedN.U + cLoadTaskSpilter.cmdURAM.bits.baseDimMinor // 对应列起始
    // 对应偏移
    val addr = m * cLoadTaskSpilter.cmdURAM.bits.majorStride + n + cLoadTaskSpilter.cmdURAM.bits.baseUramAddr
    // Swizzling 逻辑
    // 开始连线
    rreq1(i).bits.id := 0.U
    rreq1(i).bits.address := addr
    rreq1(i).valid := cLoadTaskSpilter.cmdURAM.valid &
      (cLoadTaskSpilter.cmdURAM.bits.pMajor >= fixedM.U) &
      (cLoadTaskSpilter.cmdURAM.bits.pMinor >= fixedN.U)
  })
  cLoadTaskSpilter.cmdURAM.ready := rreq1(0).ready
  cLoadTaskSpilter.cmdBuffer.ready := rresp1(0).valid
  cBuffer.extWAddr := cLoadTaskSpilter.cmdBuffer.bits.bufferAddr
  cBuffer.extWBeat := cLoadTaskSpilter.cmdBuffer.bits.beat
  cBuffer.extWe    := cLoadTaskSpilter.cmdBuffer.valid & !cLoadTaskSpilter.cmdBuffer.bits.isBLoad & rresp0(0).valid

  (0 until ports).foreach(i => {
    // Get Mapped MN - K
    val fixedM = i / portsPerLine1
    val fixedN = 2 * (i % portsPerLine1)
    val m = fixedM.U + cStoreURAMReq.bits.baseDimMajor // 对应行起始
    val n = fixedN.U + cStoreURAMReq.bits.baseDimMinor // 对应列起始
    // 对应偏移
    val addr = m * cStoreURAMReq.bits.majorStride + n + cStoreURAMReq.bits.baseUramAddr
    // Swizzling 逻辑
    // 开始连线
    wreq(i).bits.id := 0.U
    wreq(i).bits.address := addr
    wreq(i).valid := cStoreURAMReq.valid &
      (cStoreURAMReq.bits.pMajor >= fixedM.U) &
      (cStoreURAMReq.bits.pMinor >= fixedN.U)
  })
  cStoreURAMReq.ready := wreq(0).ready
  // Two-stage rdata handling
  val s1RAddr = cStoreTaskSpilter.cmdBuffer.bits.bufferAddr
  val s1RBeat = cStoreTaskSpilter.cmdBuffer.bits.beat
  val s2RAddr = RegEnable(s1RAddr, cStoreTaskSpilter.cmdBuffer.fire)
  val s2RBeat = RegEnable(s1RBeat, cStoreTaskSpilter.cmdBuffer.fire)
  val s2LastLoad = RegEnable(cStoreTaskSpilter.cmdBuffer.bits.lastLoad, cStoreTaskSpilter.cmdBuffer.fire)
//  val s2Valid = RegEnable(cStoreTaskSpilter.cmdBuffer.valid, wreq(0).ready | !s2Valid)
  val s2Valid = RegInit(0.B)
  when(wreq(0).ready | !s2Valid) {
    s2Valid := cStoreTaskSpilter.cmdBuffer.valid
  }
  val s2Deny = s2Valid & !wreq(0).ready
  cStoreTaskSpilter.cmdBuffer.ready := !s2Valid | wreq(0).ready
  cBuffer.extRe := s2Valid | cStoreTaskSpilter.cmdBuffer.valid
  cBuffer.extRAddr := Mux(s2Deny, s2RAddr, s1RAddr)
  cBuffer.extRBeat := Mux(s2Deny, s2RBeat, s1RBeat)

  // load Completion Handling
  val loadABCompQ = Module(new Queue(Bool(), 4))
  val loadCCompQ  = Module(new Queue(Bool(), 4))
  loadCCompQ.io.enq.bits := 0.B
  loadCCompQ.io.enq.valid := cLoadTaskSpilter.cmdBuffer.fire & cLoadTaskSpilter.cmdBuffer.bits.lastLoad
  loadABCompQ.io.enq.bits := abLoadTaskSpilter.cmdBuffer.bits.waitLoadC
  loadABCompQ.io.enq.valid := abLoadTaskSpilter.cmdBuffer.fire & abLoadTaskSpilter.cmdBuffer.bits.lastLoad
  loadABCompQ.io.deq.ready := !loadABCompQ.io.deq.bits | loadCCompQ.io.deq.valid
  loadCCompQ.io.deq.ready := loadABCompQ.io.deq.valid & loadABCompQ.io.deq.bits
  ldComp.valid := loadABCompQ.io.deq.valid & (!loadABCompQ.io.deq.bits | loadCCompQ.io.deq.valid)
  ldComp.bits := 0.B

  // store Completion Handling
  stComp.valid := wreq(0).ready & s2LastLoad & s2Valid
  stComp.bits := 0.B
}

object DPArrayTop extends App {
  ChiselStage.emitSystemVerilog(
    new DPArrayTop(32, 16, 20, 20, 4, 2, 8, 8),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/DPArrayTop"
    )
  )
}

