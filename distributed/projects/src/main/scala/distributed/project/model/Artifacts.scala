package distributed
package project
package model

import _root_.java.io.File
import pretty._

// TODO - Do we have enough information here?
// - Is the version even right to assume?
case class ArtifactLocation(dep: ProjectDep, local: File)
object ArtifactLocation {
  implicit object PrettyArtLoc extends PrettyPrint[ArtifactLocation] {
    def apply(l: ArtifactLocation): String = {
      val sb = new StringBuilder("{\n")
      sb append ("  location = \"%s\"\n" format(PrettyPrint(l.local)))
      sb append ("  info = %s\n" format(PrettyPrint(l.dep)))
      sb append "}"
      sb.toString
    }
  }
}


case class BuildArtifacts(artifacts: Seq[ArtifactLocation])
object BuildArtifacts {
  implicit object PrettyPrinter extends PrettyPrint[BuildArtifacts] {
    def apply(r: BuildArtifacts): String = {
      val sb = new StringBuilder("{\n")
      sb append ("  artifacts = %s\n" format(PrettyPrint(r.artifacts)))
      sb append "}"
      sb.toString
    }
  }
}