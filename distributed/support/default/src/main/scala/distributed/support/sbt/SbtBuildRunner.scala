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
import distributed.project.BuildResultFileParser


class SbtBuildRunner(base: File = new File(".sbtbuild")) extends project.BuildRunner {
  val system: String = "sbt"
  // TODO - Push in and extract dependencies
  def runBuild(b: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    SbtBuilder.buildSbtProject(dir, dependencies, base, log)
  }
}

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {
  
  def buildSbtProject(project: File, dependencies: BuildArtifacts, base: File, log: logging.Logger): BuildArtifacts = {
    makeGlobalBaseIn(base)
    IO.withTemporaryDirectory { tmpDir => 
      val resultFile = tmpDir / "results.dsbt"
      val depsFile = tmpDir / "deps.dsbt"
      IO.write(depsFile, pretty.ConfigPrint(dependencies))
      log.debug("Runing SBT build in " + project + " with depsFile " + depsFile)
      // TODO - Send in inputs, get back outputs.
      Process(Seq("sbt", 
          "-Dsbt.global.base="+base.getAbsolutePath,
          "-Dproject.build.results.file="+resultFile.getAbsolutePath,
          "-Dproject.build.deps.file="+depsFile.getAbsolutePath,
          "-Dsbt.version=0.12.0-RC1",
          "-sbt-version",
          "0.12.0-RC1",
          "-Dsbt.log.noformat=true",
          "dsbt-build"), Some(project)) ! log match {
        case 0 => log.success("Build succesful")
        case n => 
          log.err("Failure to run sbt extraction!  Error code: " + n)
          sys.error("Failure to run sbt extraction!  Error code: " + n)
      }
      
      (BuildResultFileParser parseMetaFile resultFile getOrElse
        sys.error("Failed to generate or load build results!"))
    }
  }
}