// Import Mill dependencies
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import coursier.maven.MavenRepository

// Import from each repository
import $file.dependencies.`berkeley-hardfloat`.common
import $file.common

// Define Scala and Chisel versions
object v {
  val scalaVersions = Map(
    "6.6.0" -> "2.13.15",
  )
  val chiselCrossVersions = Map(
    "6.6.0" -> (ivy"org.chipsalliance::chisel:6.6.0", ivy"org.chipsalliance:::chisel-plugin:6.6.0", ivy"edu.berkeley.cs::chiseltest:6.0.0"),
  )
}

// Define berkeley-hardfloat module
object hardfloat extends Cross[Hardfloat](v.chiselCrossVersions.keys.toSeq)
trait Hardfloat
  extends millbuild.dependencies.`berkeley-hardfloat`.common.HardfloatModule
    with Cross.Module[String] {
  def chiselModule = None
  def chiselPluginJar = None
  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)
  override def millSourcePath = os.pwd / "dependencies" / "berkeley-hardfloat" / "hardfloat"
}

object rvtng extends Cross[Rvtng](v.chiselCrossVersions.keys.toSeq)
trait Rvtng
  extends millbuild.common.RVTNGModule
    with Cross.Module[String] {
  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)

  override def millSourcePath = os.pwd

  override def resources = T.sources {
    millSourcePath / "src" / "main" / "resources"
  }

  def hardfloatModule = hardfloat(crossValue)
  def ivyDeps = super.ivyDeps()

  override def forkArgs = Seq("-Xmx256G", "-Xss192m")
  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)

  // Define tests module
  object tests extends ScalaTests with TestModule.ScalaTest {
    override def forkArgs = Seq("-Xmx256G", "-Xss192m")
    override def ivyDeps = super.ivyDeps() ++ Agg(
      v.chiselCrossVersions(chiselVersion)._2,
      v.chiselCrossVersions(chiselVersion)._3
    )
  }
}

object license extends Module {
  private val marker = "SPDX-License-Identifier: MulanPSL-2.0"
  private val thirdPartyMarker =
    "Redistribution and use in source and binary forms"

  private def scalaFiles =
    os.walk(os.pwd / "src").filter(path => os.isFile(path) && path.ext == "scala")

  private def managedScalaFiles =
    scalaFiles.filterNot(path => os.read(path).contains(thirdPartyMarker))

  def create() = T.command {
    val header = os.read(os.pwd / "license-header.txt").stripTrailing() + "\n\n"
    val updated = managedScalaFiles.filter { path =>
      val content = os.read(path)
      if (content.contains(marker)) {
        false
      } else {
        os.write.over(path, header + content)
        true
      }
    }
    T.log.info(s"Added license header to ${updated.size} Scala files")
  }

  def check() = T.command {
    val missing = managedScalaFiles.filterNot(path => os.read(path).contains(marker))
    if (missing.nonEmpty) {
      missing.foreach(path => T.log.error(path.relativeTo(os.pwd).toString))
      throw new RuntimeException(
        s"${missing.size} Scala files are missing license headers"
      )
    }
    T.log.info("All managed Scala files contain license headers")
  }
}
