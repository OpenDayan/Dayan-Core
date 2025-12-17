error id: E4E5174D330C6D976A897543671259A3
file://<WORKSPACE>/src/main/scala/rvtng/vpu/TcsrUnit.scala
### scala.ScalaReflectionException: value no_vpu_inst is not a method

occurred in the presentation compiler.



action parameters:
uri: file://<WORKSPACE>/src/main/scala/rvtng/vpu/TcsrUnit.scala
text:
```scala
package rvtng.vpu

import chisel3._
import chisel3.util._
import rvtng.vpu.GemminiISA._
import chisel3.Reg
import chisel3.util.Decoupled
import chisel3.when
import rvtng.vpu.GemminiCmd
import rvtng.vpu.GemminiISA.tcsr_xchg_nmask_cmd
import rvtng.vpu.tcsrcontent
import chisel3.Bundle
import chisel3.Flipped
import rvtng.vpu.VpuParams
import chisel3.Data
import rvtng.top.TensorResp


//tcsr模块，输入原始指令，输出加入tcsr info之后的指令
class TcsrUnit(TcsrNum: Int = 64, TcsrWidth: Int = 32, params: VpuParams) extends Module{
    import params._
    val io = IO(new Bundle{
        val cmdin = Flipped(Decoupled(new GemminiCmd(reservation_station_entries)))
        val cmdout = Decoupled(new GemminiCmd(reservation_station_entries))
        val instResp = Decoupled(new TensorResp())
    })
    
    val TCSR = Reg(Vec(64, UInt(32.W)))
    val tcsr_xchg_nmask = io.cmdin.bits.cmd.inst.funct === tcsr_xchg_nmask_cmd 
    val tcsr_cmd = tcsr_xchg_nmask && io.cmdin.valid
    val no_vpu_inst = io.cmdin.bits.cmd.inst.opcode === 
    val tcsr_w_id = io.cmdin.bits.cmd.inst.asUInt(41,32)
    val tcsr_rs1 = io.cmdin.bits.cmd.inst.rs1
    val tcsr_rs3 = io.cmdin.bits.cmd.inst.rs3
    val tcsr_rd = io.cmdin.bits.cmd.inst.rd

    when(io.cmdin.fire && tcsr_cmd){
      val data = (io.cmdin.bits.cmd.rs1 & ~io.cmdin.bits.cmd.rs3) | (TCSR(tcsr_w_id).asUInt & io.cmdin.bits.cmd.rs3)
      TCSR(tcsr_w_id) := (io.cmdin.bits.cmd.rs1 & ~io.cmdin.bits.cmd.rs3) | (TCSR(tcsr_w_id).asUInt & io.cmdin.bits.cmd.rs3)
    }

    //inst with tcsr info
    val tcsr_filter_cmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))
    tcsr_filter_cmd.valid := io.cmdin.valid && !tcsr_cmd
    tcsr_filter_cmd.bits.cmd := io.cmdin.bits.cmd
    tcsr_filter_cmd.bits.from_conv_fsm := io.cmdin.bits.from_conv_fsm
    tcsr_filter_cmd.bits.from_matmul_fsm := io.cmdin.bits.from_matmul_fsm
    tcsr_filter_cmd.bits.rob_id := io.cmdin.bits.rob_id
    
    io.cmdin.ready := tcsr_filter_cmd.ready && !tcsr_cmd || (tcsr_cmd && io.instResp.ready)
    val tcsr_reads = Wire(Vec(3, new tcsrcontent(32)))
    val tcsr_r_id = Wire(Vec(3, UInt(10.W)))

    tcsr_r_id(0) := io.cmdin.bits.cmd.inst.tcfgrs1 * 8.U
    tcsr_r_id(1) := io.cmdin.bits.cmd.inst.tcfgrs2 * 8.U
    tcsr_r_id(2) := io.cmdin.bits.cmd.inst.tcfgrs3 * 8.U

    for (i <- 0 until 3){
      tcsr_reads(i) := VecInit((0 until 8).map(j =>TCSR(tcsr_r_id(i) + j.U))).asTypeOf(new tcsrcontent(32))// Cat(TCSR(tcsr_r_id(i)), TCSR(tcsr_r_id(i) + 1.U), TCSR(tcsr_r_id(i) + 2.U), TCSR(tcsr_r_id(i) + 3.U),TCSR(tcsr_r_id(i) + 4.U), TCSR(tcsr_r_id(i) + 5.U), TCSR(tcsr_r_id(i) + 6.U), TCSR(tcsr_r_id(i) + 7.U)).asTypeOf(new tcsrcontent(32))
    }
    tcsr_filter_cmd.bits.tcfgrs := tcsr_reads

    io.cmdout <> tcsr_filter_cmd

    io.instResp.valid := tcsr_cmd
    io.instResp.bits.data := TCSR(tcsr_w_id)
    io.instResp.bits.rd := tcsr_rd
}
```


presentation compiler configuration:
Scala version: 2.13.15
Classpath:
<WORKSPACE>/.bloop/out/rvtng[6.6.0]/bloop-bsp-clients-classes/classes-Metals-K6SUspSTRSCmCRdIaeZ_RA== [exists ], <HOME>/.cache/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.1/semanticdb-javac-0.11.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/6.6.0/chisel_2.13-6.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel-plugin_2.13.15/6.6.0/chisel-plugin_2.13.15-6.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.15/scala-library-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.15/scala-reflect-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/net/jcazevedo/moultingyaml_2.13/0.4.2/moultingyaml_2.13-0.4.2.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-native_2.13/4.0.6/json4s-native_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.10.0/commons-text-1.10.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.6/data-class_2.13-0.2.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.9.2/os-lib_2.13-0.9.2.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-parallel-collections_2.13/1.0.4/scala-parallel-collections_2.13-1.0.4.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.1.0/upickle_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/1.3.0/firtool-resolver_2.13-1.3.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.13.15/scala-compiler-2.13.15.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/nscala-time/nscala-time_2.13/2.22.0/nscala-time_2.13-2.22.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/yaml/snakeyaml/1.26/snakeyaml-1.26.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-core_2.13/4.0.6/json4s-core_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-native-core_2.13/4.0.6/json4s-native-core_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.0.0/geny_2.13-1.0.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.1.0/ujson_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.1.0/upack_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.1.0/upickle-implicits_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/dev/dirs/directories/26/directories-26.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/outr/scribe_2.13/3.13.0/scribe_2.13-3.13.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.8/coursier_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils/4.12/java-diff-utils-4.12.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/jline/jline/3.26.3/jline-3.26.3.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/joda-time/joda-time/2.10.1/joda-time-2.10.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/joda/joda-convert/2.2.0/joda-convert-2.2.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-ast_2.13/4.0.6/json4s-ast_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/json4s/json4s-scalap_2.13/4.0.6/json4s-scalap_2.13-4.0.6.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/thoughtworks/paranamer/paranamer/2.8/paranamer-2.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.1.0/upickle-core_2.13-3.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/outr/perfolation_2.13/1.2.9/perfolation_2.13-1.2.9.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_2.13/0.3.1/sourcecode_2.13-0.3.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/outr/moduload_2.13/1.1.7/moduload_2.13-1.1.7.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5.2/jsoniter-scala-core_2.13-2.13.5.2.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.8/coursier-core_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.8/coursier-cache_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-proxy-setup/2.1.8/coursier-proxy-setup-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0/concurrent-reference-hash-map-1.1.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.8/coursier-util_2.13-2.1.8.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/get-coursier/jniutils/windows-jni-utils/0.3.3/windows-jni-utils-0.3.3.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-archiver/4.9.0/plexus-archiver-4.9.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-container-default/2.1.1/plexus-container-default-2.1.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/virtuslab/scala-cli/config_2.13/0.2.1/config_2.13-0.2.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.5/windows-ansi-0.0.5.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/4.0.0/plexus-utils-4.0.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-io/3.4.1/plexus-io-3.4.1.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/commons-io/commons-io/2.15.0/commons-io-2.15.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-compress/1.24.0/commons-compress-1.24.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/iq80/snappy/snappy/0.4/snappy-0.4.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.5-10/zstd-jni-1.5.5-10.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-classworlds/2.6.0/plexus-classworlds-2.6.0.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/xbean/xbean-reflect/3.7/xbean-reflect-3.7.jar [exists ], <HOME>/.cache/coursier/v1/https/repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.18/jansi-1.18.jar [exists ], <WORKSPACE>/dependencies/berkeley-hardfloat/hardfloat/compile-resources [missing ], <WORKSPACE>/.bloop/out/hardfloat[6.6.0]/bloop-bsp-clients-classes/classes-Metals-K6SUspSTRSCmCRdIaeZ_RA== [exists ], <WORKSPACE>/compile-resources [missing ]
Options:
-language:reflectiveCalls -Ymacro-annotations -deprecation -feature -Xcheckinit -Yrangepos -Xplugin-require:semanticdb




#### Error stacktrace:

```
scala.reflect.api.Symbols$SymbolApi.asMethod(Symbols.scala:240)
	scala.reflect.api.Symbols$SymbolApi.asMethod$(Symbols.scala:234)
	scala.reflect.internal.Symbols$SymbolContextApiImpl.asMethod(Symbols.scala:101)
	scala.tools.nsc.typechecker.ContextErrors$TyperContextErrors$TyperErrorGen$.MissingArgsForMethodTpeError(ContextErrors.scala:811)
	scala.tools.nsc.typechecker.Typers$Typer.adaptMethodTypeToExpr$1(Typers.scala:999)
	scala.tools.nsc.typechecker.Typers$Typer.adapt(Typers.scala:1353)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6335)
	scala.tools.nsc.typechecker.Typers$Typer.typedDefDef(Typers.scala:6584)
	scala.tools.nsc.typechecker.Typers$Typer.typed1(Typers.scala:6226)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6320)
	scala.tools.nsc.typechecker.Typers$Typer.typedStat$1(Typers.scala:6398)
	scala.tools.nsc.typechecker.Typers$Typer.$anonfun$typedStats$5(Typers.scala:3493)
	scala.tools.nsc.typechecker.Typers$Typer.$anonfun$typedStats$5$adapted(Typers.scala:3488)
	scala.reflect.internal.Scopes$Scope.foreach(Scopes.scala:455)
	scala.tools.nsc.typechecker.Typers$Typer.addSynthetics$1(Typers.scala:3488)
	scala.tools.nsc.typechecker.Typers$Typer.typedStats(Typers.scala:3556)
	scala.tools.nsc.typechecker.Typers$Typer.typedTemplate(Typers.scala:2140)
	scala.tools.nsc.typechecker.Typers$Typer.typedClassDef(Typers.scala:1978)
	scala.tools.nsc.typechecker.Typers$Typer.typed1(Typers.scala:6227)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6320)
	scala.tools.nsc.typechecker.Typers$Typer.typedStat$1(Typers.scala:6398)
	scala.tools.nsc.typechecker.Typers$Typer.$anonfun$typedStats$10(Typers.scala:3544)
	scala.tools.nsc.typechecker.Typers$Typer.typedStats(Typers.scala:3544)
	scala.tools.nsc.typechecker.Typers$Typer.typedPackageDef$1(Typers.scala:5901)
	scala.tools.nsc.typechecker.Typers$Typer.typed1(Typers.scala:6230)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6320)
	scala.tools.nsc.typechecker.Analyzer$typerFactory$TyperPhase.apply(Analyzer.scala:125)
	scala.tools.nsc.Global$GlobalPhase.applyPhase(Global.scala:483)
	scala.tools.nsc.interactive.Global$TyperRun.applyPhase(Global.scala:1369)
	scala.tools.nsc.interactive.Global$TyperRun.typeCheck(Global.scala:1362)
	scala.tools.nsc.interactive.Global.typeCheck(Global.scala:680)
	scala.meta.internal.pc.WithCompilationUnit.<init>(WithCompilationUnit.scala:24)
	scala.meta.internal.pc.SimpleCollector.<init>(PcCollector.scala:348)
	scala.meta.internal.pc.PcSemanticTokensProvider$Collector$.<init>(PcSemanticTokensProvider.scala:19)
	scala.meta.internal.pc.PcSemanticTokensProvider.Collector$lzycompute$1(PcSemanticTokensProvider.scala:19)
	scala.meta.internal.pc.PcSemanticTokensProvider.Collector(PcSemanticTokensProvider.scala:19)
	scala.meta.internal.pc.PcSemanticTokensProvider.provide(PcSemanticTokensProvider.scala:73)
	scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$semanticTokens$1(ScalaPresentationCompiler.scala:207)
	scala.meta.internal.pc.CompilerAccess.retryWithCleanCompiler(CompilerAccess.scala:182)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withSharedCompiler$1(CompilerAccess.scala:155)
	scala.Option.map(Option.scala:242)
	scala.meta.internal.pc.CompilerAccess.withSharedCompiler(CompilerAccess.scala:154)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withInterruptableCompiler$1(CompilerAccess.scala:92)
	scala.meta.internal.pc.CompilerAccess.$anonfun$onCompilerJobQueue$1(CompilerAccess.scala:209)
	scala.meta.internal.pc.CompilerJobQueue$Job.run(CompilerJobQueue.scala:152)
	java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
	java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	java.base/java.lang.Thread.run(Thread.java:840)
```
#### Short summary: 

scala.ScalaReflectionException: value no_vpu_inst is not a method