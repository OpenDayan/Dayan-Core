import mill._
import mill.scalalib._

trait HasChisel
  extends ScalaModule {
  def chiselIvy: Option[Dep]
  def chiselPluginIvy: Option[Dep]
  override def ivyDeps = super.ivyDeps() ++ Agg(chiselIvy.get) ++ Agg(chiselPluginIvy.get)
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
}

trait RVTNGModule extends HasChisel {
  def hardfloatModule : ScalaModule
  override def moduleDeps = super.moduleDeps ++ Seq(
    hardfloatModule,
  )
}