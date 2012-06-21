package distributed
package project
package model

import _root_.java.io.File
import pretty._

// TODO - Do we have enough information here?
// - Is the version even right to assume?
case class ArtifactLocation(dep: ProjectDep, local: File)
object ArtifactLocation {
  implicit object PrettyArtLoc extends ConfigPrint[ArtifactLocation] {
    def apply(l: ArtifactLocation): String = {
      val sb = new StringBuilder("{\n")
      sb append ("  location = \"%s\"\n" format(ConfigPrint(l.local)))
      sb append ("  info = %s\n" format(ConfigPrint(l.dep)))
      sb append "}"
      sb.toString
    }
  }
}


case class BuildArtifacts(artifacts: Seq[ArtifactLocation])
object BuildArtifacts {
  implicit object PrettyPrinter extends ConfigPrint[BuildArtifacts] {
    def apply(r: BuildArtifacts): String = {
      val sb = new StringBuilder("{\n")
      sb append ("  artifacts = %s\n" format(ConfigPrint(r.artifacts)))
      sb append "}"
      sb.toString
    }
  }
}