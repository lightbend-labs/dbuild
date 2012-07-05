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
}