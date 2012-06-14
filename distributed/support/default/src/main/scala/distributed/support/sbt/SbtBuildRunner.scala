package distributed
package support
package sbt

import SbtHelper._
import project.dependencies.{BuildDependencyExtractor, ExtractedDependencyFileParser}
import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process


class SbtBuildRunner(base: File = new File(".sbtbuild")) extends project.BuildRunner {
  val system: String = "sbt"
  // TODO - Push in and extract dependencies
  def runBuild(b: Build, dir: File, log: logging.Logger): Unit = {
    SbtBuilder.buildSbtProject(dir, base)
  }
}

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {
  
  def buildSbtProject(project: File, base: File): Unit = {
    makeGlobalBaseIn(base)
    // TODO - Send in inputs, get back outputs.
    Process(Seq("sbt", 
        "-Dsbt.global.base="+base.getAbsolutePath,
        //"-no-global",
        //"-Dsbt.version=0.12.0-RC1",
        "-sbt-version",
        "0.12.0-RC1",
        "-Dsbt.log.noformat=true",
        "print-deps"), Some(project)).! match {
      case 0 => ()
      case n => sys.error("Failure to run sbt extraction!  Error code: " + n)
    }
  }
}