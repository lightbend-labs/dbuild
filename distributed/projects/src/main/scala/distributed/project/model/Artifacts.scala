package distributed
package project
package model

import _root_.java.io.File
import config.{ConfigPrint, ConfigRead}
import ConfigPrint.makeMember
import ConfigRead.readMember
import sbt.Types.:+:
import sbt.HNil

// TODO - Do we have enough information here?
// - Is the version even right to assume?
case class ArtifactLocation(dep: ProjectRef, local: File, version: String, buildTime: Double = 0.0)
object ArtifactLocation {
  implicit object PrettyArtLoc extends ConfigPrint[ArtifactLocation] {
    def apply(l: ArtifactLocation): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("location", l.local)
      sb append ","
      sb append makeMember("info", l.dep)
      sb append ","
      sb append makeMember("version", l.version)
      sb append ","
      sb append makeMember("buildTime", l.buildTime)
      sb append "}"
      sb.toString
    }
  }
  /** Extractor from a ConfigValue. */
  implicit object Configured extends ConfigRead[ArtifactLocation] {
    import config._
    val Members = (
      readMember[File]("location") :^:
      readMember[ProjectRef]("info") :^:
      readMember[String]("version") :^:
      readMember[Double]("buildTime")
    )
    def unapply(c: ConfigValue): Option[ArtifactLocation] = {
      c match {
        case Members(file :+: dep :+: version :+: time :+: HNil) =>
          Some(ArtifactLocation(dep, file, version, time))
        case _ => None
      }
    }
  }  
}

// TODO - Is this a good idea?
case class BuildArtifacts(artifacts: Seq[ArtifactLocation], localRepo: File)
object BuildArtifacts {
  implicit object PrettyPrinter extends ConfigPrint[BuildArtifacts] {
    def apply(r: BuildArtifacts): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("artifacts", r.artifacts)
      sb append ","
      sb append makeMember("localRepo", r.localRepo)
      sb append "}"
      sb.toString
    }
  }
  implicit object Configured extends ConfigRead[BuildArtifacts] {
    import config._
    val Members = (
      readMember[Seq[ArtifactLocation]]("artifacts") :^:
      readMember[java.io.File]("localRepo")
    )
    def unapply(in: ConfigValue): Option[BuildArtifacts] = in match {
      case Members(artifacts :+: repo :+: HNil) =>
        Some(BuildArtifacts(artifacts, repo))
      case _ => None
    }
  }
}