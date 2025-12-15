error id: 5CD3312933D43F208E85DD7A4D8F80F1
file://<WORKSPACE>/src/main/scala/rvtng/top/RVTNGInstDispatch.scala
### scala.ScalaReflectionException: value decodeIsVpu is not a method

occurred in the presentation compiler.



action parameters:
uri: file://<WORKSPACE>/src/main/scala/rvtng/top/RVTNGInstDispatch.scala
text:
```scala
package rvtng.top

import chisel3._
import chisel3.util._

class RVTNGInstDispatch extends Module {

  val instReq = IO(Flipped(Decoupled(new TensorInst)))
  val instResp = IO(Decoupled(new TensorResp))

  val matReq = IO(Decoupled(new TensorInst))
  val matResp = IO(Flipped(Decoupled(new TensorResp)))
  val vecReq = IO(Decoupled(new TensorInst))
  val vecResp = IO(Flipped(Decoupled(new TensorResp)))
  val dmaReq = IO(Decoupled(new TensorInst))
  val dmaResp = IO(Flipped(Decoupled(new TensorResp)))
  val sbReq = IO(Decoupled(new TensorInst))
  val sbResp = IO(Flipped(Decoupled(new TensorResp)))

  private val instQ = Queue(instReq, 2)

  // Decoder Logic here
  private val inst = instQ.bits.instWord
  private val decodeIsTCSRorScoreboard = inst(31, 28) === 0.U

  private val decodeIsScoreboard = decodeIsTCSRorScoreboard & inst(26, 25) === 3.U
  private val decodeIsMat = (decodeIsTCSRorScoreboard & inst(26, 25) === 0.U) | (inst(31, 28) === 1.U)
  private val decodeIsDma = (decodeIsTCSRorScoreboard & inst(26, 25) === 2.U) | (inst(31, 29) === 1.U)
  private val decodeIsVec = (decodeIsTCSRorScoreboard & inst(26, 25) === 1.U) | (inst(31, 30) > 0.U)
  private val decodeIsVpu = inst(6,0) === 
  private val illegal = !(decodeIsScoreboard | decodeIsMat | decodeIsDma | decodeIsVec)
  chisel3.assert(!(illegal & instQ.valid))

  sbReq.bits  := instQ.bits
  matReq.bits := instQ.bits
  dmaReq.bits := instQ.bits
  vecReq.bits := instQ.bits
  sbReq.valid  := instQ.valid & decodeIsScoreboard
  matReq.valid := instQ.valid & decodeIsMat
  dmaReq.valid := instQ.valid & decodeIsDma
  vecReq.valid := instQ.valid & decodeIsVec

  instQ.ready := (sbReq.ready  & decodeIsScoreboard) | (matReq.ready & decodeIsMat) |
                 (dmaReq.ready & decodeIsDma)        | (vecReq.ready & decodeIsVec) | illegal

  // RR-Arbiter of write-back
  private val arb = Module(new RRArbiter(instResp.bits.cloneType, 4)).io
  arb.in(0) <> sbResp
  arb.in(1) <> matResp
  arb.in(2) <> dmaResp
  arb.in(3) <> vecResp
  instResp <> arb.out

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
	java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	java.base/java.lang.Thread.run(Thread.java:1623)
```
#### Short summary: 

scala.ScalaReflectionException: value decodeIsVpu is not a method