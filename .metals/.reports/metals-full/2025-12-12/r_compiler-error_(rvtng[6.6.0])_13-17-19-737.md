error id: 97363ABA0D457719FDB8837F11F6F4C9
file://<WORKSPACE>/src/main/scala/rvtng/vpu/rvtwrapper.scala
### java.util.NoSuchElementException: head of empty String

occurred in the presentation compiler.



action parameters:
offset: 2879
uri: file://<WORKSPACE>/src/main/scala/rvtng/vpu/rvtwrapper.scala
text:
```scala
package rvtng.vpu

import chisel3._
import chisel3.util._

import Util._
import rvtng.top.ScoreboardComp
import rvtng.top.TensorInst
import rvtng.top.TensorResp
import rvtng.uram.AdvURAMReq
import rvtng.uram.AdvURAMResp
import rvtng.uram.AdvURAMWResp

class rvtwrapper(params: VpuParams) extends Module{
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

    val TCSR = Reg(Vec(tcsrNums*tcsrdims, UInt(tcsrWidth.W)))
  val tcsr_xchg_nmask = raw_cmd.bits.cmd.inst.funct === tcsr_xchg_nmask_cmd 
  val tcsr_cmd = tcsr_xchg_nmask && raw_cmd.valid
  val tcsr_w_id = raw_cmd.bits.cmd.inst.asUInt(41,32)
  val tcsr_rs1 = raw_cmd.bits.cmd.inst.rs1
  val tcsr_rs3 = raw_cmd.bits.cmd.inst.rs3
  val tcsr_rd = raw_cmd.bits.cmd.inst.rd
  val gemmini_resp = Wire(new RoCCResponse)
  gemmini_resp.rd := tcsr_rd
  gemmini_resp.data := TCSR(tcsr_w_id)
  io.resp.valid := raw_cmd.valid //目前只有tcfg指令写回寄存器，所以简化逻辑
  io.resp.bits := gemmini_resp
  val enTraceWrite = PlusArg("trace_rvt_tcsr", default = 0, width = 1)
  when(raw_cmd.fire && tcsr_cmd){
    val data = (raw_cmd.bits.cmd.rs1 & ~raw_cmd.bits.cmd.rs3) | (TCSR(tcsr_w_id).asUInt & raw_cmd.bits.cmd.rs3)
    TCSR(tcsr_w_id) := (raw_cmd.bits.cmd.rs1 & ~raw_cmd.bits.cmd.rs3) | (TCSR(tcsr_w_id).asUInt & raw_cmd.bits.cmd.rs3)
    when(enTraceWrite.asBool) {
      printf(cf"[TRACE] TCSR[0x$tcsr_w_id%x] W: 0x$data%x\n")
    }
  }
  val tcsr_filter_cmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))
  tcsr_filter_cmd.valid := raw_cmd.valid && !tcsr_cmd
  tcsr_filter_cmd.bits.cmd := raw_cmd.bits.cmd
  tcsr_filter_cmd.bits.from_conv_fsm := raw_cmd.bits.from_conv_fsm
  tcsr_filter_cmd.bits.from_matmul_fsm := raw_cmd.bits.from_matmul_fsm
  tcsr_filter_cmd.bits.rob_id := raw_cmd.bits.rob_id
  
  raw_cmd.ready := tcsr_filter_cmd.ready && !tcsr_cmd || (tcsr_cmd && io.resp.ready)
  val tcsr_reads = Wire(Vec(3, new tcsrcontent(@@tcsrWidth)))
  val tcsr_r_id = Wire(Vec(3, UInt(10.W)))

  tcsr_r_id(0) := raw_cmd.bits.cmd.inst.tcfgrs1 * 8.U
  tcsr_r_id(1) := raw_cmd.bits.cmd.inst.tcfgrs2 * 8.U
  tcsr_r_id(2) := raw_cmd.bits.cmd.inst.tcfgrs3 * 8.U

  for (i <- 0 until 3){
    tcsr_reads(i) := Cat(TCSR(tcsr_r_id(i)), TCSR(tcsr_r_id(i) + 1.U), TCSR(tcsr_r_id(i) + 2.U), TCSR(tcsr_r_id(i) + 3.U),TCSR(tcsr_r_id(i) + 4.U), TCSR(tcsr_r_id(i) + 5.U), TCSR(tcsr_r_id(i) + 6.U), TCSR(tcsr_r_id(i) + 7.U)).asTypeOf(new tcsrcontent(tcsrWidth))
  }
  tcsr_filter_cmd.bits.tcfgrs := tcsr_reads
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