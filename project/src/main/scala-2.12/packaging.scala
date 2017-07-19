import sbt.librarymanagement.PublishConfiguration
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
      PublishConfiguration(p.publishMavenStyle, p.deliverIvyPattern, p.status,
                           p.configurations, p.resolverName,
                           Vector[scala.Tuple2[sbt.librarymanagement.Artifact, java.io.File]](),
                           Vector[String](), p.logging, p.overwrite)
    }
    // TODO: fuse this with skip212()
  )
}
