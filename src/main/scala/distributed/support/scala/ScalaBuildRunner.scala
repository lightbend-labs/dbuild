package distributed
package support
package scala

import project.model._
import java.io.File
import sys.process._
import _root_.sbt.Path._


object ScalaBuildRunner extends project.BuildRunner {
  val system: String = "scala"
  // TODO - Publish to an ok repository for others to find the artifacts...
  def runBuild(b: Build, dir: File, log: logging.Logger): Unit = {
    Process(Seq("ant", "distpack-opt"), Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    // Now deploy, but to *our* repo?
    Process(Seq("ant", "deploy.snapshot.local"), Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
  }
}