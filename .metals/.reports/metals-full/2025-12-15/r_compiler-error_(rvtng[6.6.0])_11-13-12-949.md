error id: 97363ABA0D457719FDB8837F11F6F4C9
file://<WORKSPACE>/src/main/scala/rvtng/top/RVTNGTop.scala
### java.util.NoSuchElementException: head of empty String

occurred in the presentation compiler.



action parameters:
offset: 3639
uri: file://<WORKSPACE>/src/main/scala/rvtng/top/RVTNGTop.scala
text:
```scala
package rvtng.top

import chisel3._
import chisel3.util._
import rvtng.uram.Adv2R1WURAM
import rvtng.vec.{VecParams, VecTop}
//import rvtng.mat.{MatParams, MatTop}
import rvtng.neomat.{MatParams, MatTop}
import rvtng.dma.{DMAParams, DMATop}
import _root_.circt.stage.ChiselStage
import rvtng.bus.{AXI4Bundle, AXI4BundleParameters, DPIAXI4}
import rvtng.vpu._

// Full 8-TFlops/GHz/Core Implementation
class RVTNGTop(matParams: MatParams, vecParams: VecParams, dmaParams: DMAParams, vpuParams: VpuParams, name: String = "RVTNGTop") extends Module {
  // 实例化 MAT、Vec、DMA、URAM，并完成顶层指令分发、Scoreboard 实现。

  private val axi4Parms = AXI4BundleParameters(
    addressBits = dmaParams.addressBits, dataBits = dmaParams.dataBits, idBits = dmaParams.sourceBits
  )

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  val axi4 = IO(new AXI4Bundle(axi4Parms))

  override def desiredName: String = name
  private val uramPort = (vecParams.laneWidth * 32 / 2) / 64
  private val uramPortPerChannel = vecParams.uramPortPerChannel
  private val uramChannel = uramPort / uramPortPerChannel

  // Scoreboard
  private val sb = Module(new RVTNGScoreBoard(8, 3))

  // URAM Instance
  // URAM RP0: MAT.B,  VEC.L
  // URAM RP1: MAT.AC, LSU.L
  // URAM WP0: MAT.C,  VEC.S, LSU.S
  private val uram = Module(new Adv2R1WURAM(
    R0Port = 2, R1Port = 2, W0Port = 3, GroupCount = vecParams.uramPortPerChannel,
    fifoSize = 16, N = uramPort, addrWidth = 24, idWidth = dmaParams.sourceBits, dataWidth = 64
  ))

  // SubModule Instance
  // private val vec = Module(new VecTop(vecParams))
  private val vpu = Module(new VPUTop[Float](vpuParams))
  private val mat = Module(new MatTop(matParams)).io
  private val dma = Module(new DMATop(dmaParams, true))

  // Instruction Dispatch logic
  private val disp = Module(new RVTNGInstDispatch)
  disp.instReq  <> instReq
  disp.instResp <> instResp

  // Mat Connection
  mat.instReq <> disp.matReq
  mat.instResp <> disp.matResp
  mat.sbComp <> sb.sbComp(0)
  mat.uramRReq0 <> uram.r0req_i(0)
  mat.uramRResp0 <> uram.r0resp_o(0)
  mat.uramRReq1 <> uram.r1req_i(0)
  mat.uramRResp1 <> uram.r1resp_o(0)
//  mat.uramWReq <> uram.wreq_i(0)
//  mat.uramWResp <> uram.wresp_o(0)
//  uram.wdata_i(0) := mat.uramWData

  for(i <- 0 until matParams.busChannel * uramPortPerChannel) {
    if(i < matParams.busWriteChannel * uramPortPerChannel) {
      // Avoid DMA Write Port
      uram.wreq_i(0)( (matParams.busChannel - matParams.busWriteChannel) * uramPortPerChannel + i) <> mat.uramWReq(i)
      uram.wdata_i(0)((matParams.busChannel - matParams.busWriteChannel) * uramPortPerChannel + i) := mat.uramWData(i)
    } else {
      uram.wreq_i(0)(i - matParams.busWriteChannel * uramPortPerChannel).valid := 0.B
      uram.wreq_i(0)(i - matParams.busWriteChannel * uramPortPerChannel).bits := DontCare
      uram.wdata_i(0)(i - matParams.busWriteChannel * uramPortPerChannel) := DontCare
    }
  }
  for(i <- 0 until matParams.busChannel) {
    if(i < matParams.busWriteChannel) {
      mat.uramWResp(i) <> uram.wresp_o(0)(matParams.busChannel - matParams.busWriteChannel + i)
    } else {
      uram.wresp_o(0)(i - matParams.busWriteChannel).ready := 1.B
    }
  }

  // Vec Connection
  vec.instReq <> disp.vecReq
  vec.instResp <> disp.vecResp
  vec.sbComp <> sb.sbComp(1)
  vec.uramRReq <> uram.r0req_i(1)
  vec.uramRResp <> uram.r0resp_o(1)
  vec.uramWReq <> uram.wreq_i(1)
  vec.uramWResp <> uram.wresp_o(1)
  uram.wdata_i(1) := vec.uramWData
  vpu.instReq <> disp.vpuReq
  vec.instResp <> disp.vecResp
  vec.sbComp <> sb.sbComp(1)
  vec.uramRReq <> uram.r0req_i(1)
  @@vec.uramRResp <> uram.r0resp_o(1)
  vec.uramWReq <> uram.wreq_i(1)
  vec.uramWResp <> uram.wresp_o(1)

  // DMA Connection
  dma.instReq <> disp.dmaReq
  dma.instResp <> disp.dmaResp
  dma.sbComp <> sb.sbComp(2)
  dma.uramRReq <> uram.r1req_i(1)
  dma.uramRResp <> uram.r1resp_o(1)
  dma.uramWReq <> uram.wreq_i(2)
  dma.uramWResp <> uram.wresp_o(2)
  dma.axi4 <> axi4
  uram.wdata_i(2) := dma.uramWData

  // Scoreboard Connection
  sb.instReq <> disp.sbReq
  sb.instResp <> disp.sbResp
}

class RVTNGTestTop() extends Module {
  private val matParams = MatParams()
  private val vecParams = VecParams()
  private val dmaParams = DMAParams()
  private val vpuParams = VpuParams()
  private val core = Module(new RVTNGTop(matParams, vecParams, dmaParams, vpuParams))
  private val axi4Parms = AXI4BundleParameters(
    addressBits = dmaParams.addressBits, dataBits = dmaParams.dataBits, idBits = dmaParams.sourceBits
  )

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))
  // DPI Instance
  private val axi4 = Module(new DPIAXI4(axi4Parms, 0)).axi
  core.instReq <> instReq
  core.instResp <> instResp
  core.axi4 <> axi4
}

object RVTNGTestTop extends App {
  ChiselStage.emitSystemVerilog(
    new RVTNGTestTop(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/RVTNGTestTop"
    )
  )
}

```


presentation compiler configuration:
Scala version: 2.13.15
Classpath:
<WORKSPACE>/out/rvtng/6.6.0/compiledClassesAndSemanticDbFiles.dest [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/chipsalliance/chisel_2.13/6.6.0/chisel_2.13-6.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/chipsalliance/chisel-plugin_2.13.15/6.6.0/chisel-plugin_2.13.15-6.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/scala-lang/scala-library/2.13.15/scala-library-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/scala-lang/scala-reflect/2.13.15/scala-reflect-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/net/jcazevedo/moultingyaml_2.13/0.4.2/moultingyaml_2.13-0.4.2.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/json4s/json4s-native_2.13/4.0.6/json4s-native_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/apache/commons/commons-text/1.10.0/commons-text-1.10.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/github/alexarchambault/data-class_2.13/0.2.6/data-class_2.13-0.2.6.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/os-lib_2.13/0.9.2/os-lib_2.13-0.9.2.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/scala-lang/modules/scala-parallel-collections_2.13/1.0.4/scala-parallel-collections_2.13-1.0.4.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/upickle_2.13/3.1.0/upickle_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/chipsalliance/firtool-resolver_2.13/1.3.0/firtool-resolver_2.13-1.3.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/scala-lang/scala-compiler/2.13.15/scala-compiler-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/github/nscala-time/nscala-time_2.13/2.22.0/nscala-time_2.13-2.22.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/yaml/snakeyaml/1.26/snakeyaml-1.26.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/json4s/json4s-core_2.13/4.0.6/json4s-core_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/json4s/json4s-native-core_2.13/4.0.6/json4s-native-core_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/geny_2.13/1.0.0/geny_2.13-1.0.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/ujson_2.13/3.1.0/ujson_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/upack_2.13/3.1.0/upack_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/upickle-implicits_2.13/3.1.0/upickle-implicits_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/dev/dirs/directories/26/directories-26.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/outr/scribe_2.13/3.13.0/scribe_2.13-3.13.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/get-coursier/coursier_2.13/2.1.8/coursier_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/github/java-diff-utils/java-diff-utils/4.12/java-diff-utils-4.12.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/jline/jline/3.26.3/jline-3.26.3.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/joda-time/joda-time/2.10.1/joda-time-2.10.1.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/joda/joda-convert/2.2.0/joda-convert-2.2.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/json4s/json4s-ast_2.13/4.0.6/json4s-ast_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/json4s/json4s-scalap_2.13/4.0.6/json4s-scalap_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/thoughtworks/paranamer/paranamer/2.8/paranamer-2.8.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/upickle-core_2.13/3.1.0/upickle-core_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/outr/perfolation_2.13/1.2.9/perfolation_2.13-1.2.9.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/lihaoyi/sourcecode_2.13/0.3.1/sourcecode_2.13-0.3.1.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/outr/moduload_2.13/1.1.7/moduload_2.13-1.1.7.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5.2/jsoniter-scala-core_2.13-2.13.5.2.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/get-coursier/coursier-core_2.13/2.1.8/coursier-core_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/get-coursier/coursier-cache_2.13/2.1.8/coursier-cache_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/get-coursier/coursier-proxy-setup/2.1.8/coursier-proxy-setup-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0/concurrent-reference-hash-map-1.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/get-coursier/coursier-util_2.13/2.1.8/coursier-util_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/get-coursier/jniutils/windows-jni-utils/0.3.3/windows-jni-utils-0.3.3.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/codehaus/plexus/plexus-archiver/4.9.0/plexus-archiver-4.9.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/codehaus/plexus/plexus-container-default/2.1.1/plexus-container-default-2.1.1.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/virtuslab/scala-cli/config_2.13/0.2.1/config_2.13-0.2.1.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.5/windows-ansi-0.0.5.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/javax/inject/javax.inject/1/javax.inject-1.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/codehaus/plexus/plexus-utils/4.0.0/plexus-utils-4.0.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/codehaus/plexus/plexus-io/3.4.1/plexus-io-3.4.1.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/commons-io/commons-io/2.15.0/commons-io-2.15.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/apache/commons/commons-compress/1.24.0/commons-compress-1.24.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/iq80/snappy/snappy/0.4/snappy-0.4.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/tukaani/xz/1.9/xz-1.9.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/com/github/luben/zstd-jni/1.5.5-10/zstd-jni-1.5.5-10.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/codehaus/plexus/plexus-classworlds/2.6.0/plexus-classworlds-2.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/apache/xbean/xbean-reflect/3.7/xbean-reflect-3.7.jar [exists ], <HOME>/.cache/coursier/v1/https/maven.aliyun.com/repository/central/org/fusesource/jansi/jansi/1.18/jansi-1.18.jar [exists ], <WORKSPACE>/dependencies/berkeley-hardfloat/hardfloat/compile-resources [missing ], <WORKSPACE>/out/hardfloat/6.6.0/compile.dest/classes [exists ], <WORKSPACE>/compile-resources [missing ]
Options:
-language:reflectiveCalls -Ymacro-annotations -deprecation -feature -Xcheckinit




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
	java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	java.base/java.lang.Thread.run(Thread.java:1623)
```
#### Short summary: 

java.util.NoSuchElementException: head of empty String