error id: 2231967A81E4FA8A9DD04E7BCC30AB2E
file://<WORKSPACE>/src/main/scala/rvtng/vpu/VPUTop.scala
### java.util.NoSuchElementException: head of empty String

occurred in the presentation compiler.



action parameters:
offset: 3225
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
    val writeio = new ScratchpadWriteIO(sp_bank_entries, row_width, (row_width / (aligned_to * 8)) max 1)
    val last = Bool()
  }

  val WriteReqQueue = Module(new Queue(new WriteReq, 16))
  WriteReqQueue.io.enq.valid := ElementwiseUnit.io.uram_write.valid
  @@ElementwiseUnit.io.uram_write.ready := WriteReqQueue.io.enq.ready
  WriteReqQueue.io.enq.bits.writeio := ElementwiseUnit.io.uram_write

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


    for( i <- 0 until uramPort){
      if(i < computeunit_len*acc_width/(8*8)){
        uramRReq(i).valid := VPU.io.srams.read.req.valid
        uramRReq(i).bits.address := VPU.io.srams.read.req.bits.addr + (i * 8).U
        uramRReq(i).bits.id := 0.U
        VPU.io.srams.read.req.ready := uramRReq(i).ready

        uramWReq(i).valid := Mux(VPU.io.srams.write.mask(i * 8), VPU.io.srams.write.valid, false.B)
        uramWReq(i).bits.address := VPU.io.srams.write.addr + (i * 8).U
        uramWReq(i).bits.id := 0.U

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


presentation compiler configuration:
Scala version: 2.13.15
Classpath:
<WORKSPACE>/.bloop/out/rvtng[6.6.0]/bloop-bsp-clients-classes/classes-Metals-1fVtZgMqSpGQYLgW2s7_xQ== [exists ], <HOME>/.cache/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.1/semanticdb-javac-0.11.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/6.6.0/chisel_2.13-6.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel-plugin_2.13.15/6.6.0/chisel-plugin_2.13.15-6.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.15/scala-library-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.15/scala-reflect-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/net/jcazevedo/moultingyaml_2.13/0.4.2/moultingyaml_2.13-0.4.2.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-native_2.13/4.0.6/json4s-native_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.10.0/commons-text-1.10.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.6/data-class_2.13-0.2.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.9.2/os-lib_2.13-0.9.2.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-parallel-collections_2.13/1.0.4/scala-parallel-collections_2.13-1.0.4.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.1.0/upickle_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/1.3.0/firtool-resolver_2.13-1.3.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.13.15/scala-compiler-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/nscala-time/nscala-time_2.13/2.22.0/nscala-time_2.13-2.22.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/yaml/snakeyaml/1.26/snakeyaml-1.26.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-core_2.13/4.0.6/json4s-core_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-native-core_2.13/4.0.6/json4s-native-core_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.0.0/geny_2.13-1.0.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.1.0/ujson_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.1.0/upack_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.1.0/upickle-implicits_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/dev/dirs/directories/26/directories-26.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/outr/scribe_2.13/3.13.0/scribe_2.13-3.13.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.8/coursier_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils/4.12/java-diff-utils-4.12.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/jline/jline/3.26.3/jline-3.26.3.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/joda-time/joda-time/2.10.1/joda-time-2.10.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/joda/joda-convert/2.2.0/joda-convert-2.2.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-ast_2.13/4.0.6/json4s-ast_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-scalap_2.13/4.0.6/json4s-scalap_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/thoughtworks/paranamer/paranamer/2.8/paranamer-2.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.1.0/upickle-core_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/outr/perfolation_2.13/1.2.9/perfolation_2.13-1.2.9.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_2.13/0.3.1/sourcecode_2.13-0.3.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/outr/moduload_2.13/1.1.7/moduload_2.13-1.1.7.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5.2/jsoniter-scala-core_2.13-2.13.5.2.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.8/coursier-core_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.8/coursier-cache_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-proxy-setup/2.1.8/coursier-proxy-setup-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0/concurrent-reference-hash-map-1.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.8/coursier-util_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/jniutils/windows-jni-utils/0.3.3/windows-jni-utils-0.3.3.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-archiver/4.9.0/plexus-archiver-4.9.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-container-default/2.1.1/plexus-container-default-2.1.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/virtuslab/scala-cli/config_2.13/0.2.1/config_2.13-0.2.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.5/windows-ansi-0.0.5.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/4.0.0/plexus-utils-4.0.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-io/3.4.1/plexus-io-3.4.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/commons-io/commons-io/2.15.0/commons-io-2.15.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-compress/1.24.0/commons-compress-1.24.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/iq80/snappy/snappy/0.4/snappy-0.4.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.5-10/zstd-jni-1.5.5-10.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-classworlds/2.6.0/plexus-classworlds-2.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/xbean/xbean-reflect/3.7/xbean-reflect-3.7.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.18/jansi-1.18.jar [exists ], <WORKSPACE>/dependencies/berkeley-hardfloat/hardfloat/compile-resources [missing ], <WORKSPACE>/.bloop/out/hardfloat[6.6.0]/bloop-bsp-clients-classes/classes-Metals-1fVtZgMqSpGQYLgW2s7_xQ== [exists ], <WORKSPACE>/compile-resources [missing ]
Options:
-language:reflectiveCalls -Ymacro-annotations -deprecation -feature -Xcheckinit -Yrangepos -Xplugin-require:semanticdb




#### Error stacktrace:

```
scala.collection.StringOps$.head$extension(StringOps.scala:1124)
	scala.meta.internal.metals.ClassfileComparator.compare(ClassfileComparator.scala:30)
	scala.meta.internal.metals.ClassfileComparator.compare(ClassfileComparator.scala:3)
	java.base/java.util.PriorityQueue.siftUpUsingComparator(PriorityQueue.java:660)
	java.base/java.util.PriorityQueue.siftUp(PriorityQueue.java:637)
	java.base/java.util.PriorityQueue.offer(PriorityQueue.java:330)
	java.base/java.util.PriorityQueue.add(PriorityQueue.java:311)
	scala.meta.internal.metals.ClasspathSearch.$anonfun$search$3(ClasspathSearch.scala:32)
	scala.meta.internal.metals.ClasspathSearch.$anonfun$search$3$adapted(ClasspathSearch.scala:26)
	scala.collection.IterableOnceOps.foreach(IterableOnce.scala:630)
	scala.collection.IterableOnceOps.foreach$(IterableOnce.scala:628)
	scala.collection.AbstractIterator.foreach(Iterator.scala:1313)
	scala.meta.internal.metals.ClasspathSearch.search(ClasspathSearch.scala:26)
	scala.meta.internal.metals.WorkspaceSymbolProvider.search(WorkspaceSymbolProvider.scala:107)
	scala.meta.internal.metals.MetalsSymbolSearch.search$1(MetalsSymbolSearch.scala:114)
	scala.meta.internal.metals.MetalsSymbolSearch.search(MetalsSymbolSearch.scala:118)
	scala.meta.internal.pc.AutoImportsProvider.autoImports(AutoImportsProvider.scala:58)
	scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$autoImports$1(ScalaPresentationCompiler.scala:399)
	scala.meta.internal.pc.CompilerAccess.withSharedCompiler(CompilerAccess.scala:148)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withInterruptableCompiler$1(CompilerAccess.scala:92)
	scala.meta.internal.pc.CompilerAccess.$anonfun$onCompilerJobQueue$1(CompilerAccess.scala:209)
	scala.meta.internal.pc.CompilerJobQueue$Job.run(CompilerJobQueue.scala:152)
	java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
	java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	java.base/java.lang.Thread.run(Thread.java:840)
```
#### Short summary: 

java.util.NoSuchElementException: head of empty String