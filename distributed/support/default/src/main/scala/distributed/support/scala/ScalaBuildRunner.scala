package distributed
package support
package scala

import project.model._
import _root_.java.io.File
import sys.process._
import _root_.sbt.Path._


object ScalaBuildRunner extends project.BuildRunner {
  val system: String = "scala"
    
  val scalaOrg = "org.scala-lang"
  // TODO - Publish to an ok repository for others to find the artifacts...
  def runBuild(b: Build, dir: File, log: logging.Logger): BuildResults = {
    Process(Seq("ant", "distpack-opt"), Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    // Now, return hardcoded results.
    val libDir = dir / "build" / "pack" / "lib"
    BuildResults(Seq(
      ArtifactLocation(ProjectDep(scalaOrg, "scala-library"), libDir / "scala-library.jar"),
      ArtifactLocation(ProjectDep(scalaOrg, "scala-reflect"), libDir / "scala-reflect.jar"),
      ArtifactLocation(ProjectDep(scalaOrg, "scala-compiler"), libDir / "scala-compiler.jar"),
      ArtifactLocation(ProjectDep(scalaOrg, "scala-actors"), libDir / "scala-actors.jar")
    ))
  }
}