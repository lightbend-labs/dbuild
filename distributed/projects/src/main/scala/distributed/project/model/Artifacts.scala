package distributed
package project
package model

import _root_.java.io.File
import config.ConfigPrint
import ConfigPrint.makeMember

// TODO - Do we have enough information here?
// - Is the version even right to assume?
case class ArtifactLocation(dep: ProjectRef, local: File, version: String)
object ArtifactLocation {
  implicit object PrettyArtLoc extends ConfigPrint[ArtifactLocation] {
    def apply(l: ArtifactLocation): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("location", l.local)
      sb append ","
      sb append makeMember("info", l.dep)
      sb append ","
      sb append makeMember("version", l.version)
      sb append "}"
      sb.toString
    }
  }
  /** Extractor from a ConfigValue. */
  object Configured {
    import config._
    def unapply(c: ConfigValue): Option[ArtifactLocation] = {
      c match {
        case c: ConfigObject =>
          (c get "location", c get "info", c get "version") match {
            case (ConfigString(file), ProjectRef.Configured(dep), ConfigString(version)) => 
              Some(ArtifactLocation(dep, new java.io.File(file), version))
            case _ => None
          }
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
  object Configured {
    import config._
    def unapply(in: ConfigValue): Option[BuildArtifacts] = in match {
      case c: ConfigObject =>
        for {
          artifacts <- parseArtifacts(c get "artifacts")
          localRepo <- parseRepo(c get "localRepo")
        } yield BuildArtifacts(artifacts, localRepo)
      case _ => None
    }
    private def parseRepo(c: ConfigValue): Option[java.io.File] = c match {
      case ConfigString(file) => Some(new java.io.File(file))
      case _ => None
    }
    private def parseArtifacts(c: ConfigValue): Option[Seq[ArtifactLocation]] = c match {
      case ConfigList(configs) =>
        Some(configs collect {          
          case ArtifactLocation.Configured(loc) => loc
        })
      case _ => None
    }
  }
}