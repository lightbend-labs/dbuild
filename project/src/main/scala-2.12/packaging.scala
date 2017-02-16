import sbt.internal.librarymanagement.PublishConfiguration
import sbt.Keys._
import sbt.Project

object Packaging {
  def settings(build:Project, repo:Project): Seq[sbt.Setting[_]] = Seq(
    // disable the publication of artifacts in dist if 2.12
    // (we only retain the correct launcher, which is the
    // one generated using 2.10)
    // This is a pretty ugly hack, but it is quite difficult to prevent sbt from
    // skipping publishing completely (including the ivy file) upon a given condition.
    publishConfiguration := {
      val p = publishConfiguration.value
      new PublishConfiguration(None,p.resolverName,Map.empty,Vector[String](),p.logging)
    }
    // TODO: fuse this with skip212()
  )
}
