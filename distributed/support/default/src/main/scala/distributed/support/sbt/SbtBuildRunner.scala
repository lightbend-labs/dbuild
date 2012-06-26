package distributed
package support
package sbt

import SbtHelper._
import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import config.makeConfigString

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {
  
  def writeRepoFile(config: File, repo: File): Unit = {
    val sb = new StringBuilder("[repositories]\n")
    sb append  "  local\n"
    sb append ("  ivy-build-local: file://%s, [organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext]\n" format (repo.getAbsolutePath))
    sb append ("  mvn-build-local: file://%s\n" format (repo.getAbsolutePath))
    sb append  "  maven-central\n"
    sb append  "  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots\n"
    sb append  "  java-annoying-cla-shtuff: http://download.java.net/maven/2/\n"
    // TODO - Typesafe repositories? ... NAH
    IO.write(config, sb.toString)
  } 
    
  
  def buildSbtProject(project: File, dependencies: BuildArtifacts, base: File, log: logging.Logger): BuildArtifacts = {
    makeGlobalBaseIn(base)
    IO.withTemporaryDirectory { tmpDir => 
      val resultFile = tmpDir / "results.dsbt"
      val depsFile = tmpDir / "deps.dsbt"
      val repoFile = tmpDir / "repositories"
      
      IO.write(depsFile, makeConfigString(dependencies))
      writeRepoFile(repoFile, dependencies.localRepo)
      log.debug("Runing SBT build in " + project + " with depsFile " + depsFile)
      // TODO - Send in inputs, get back outputs.
      Process(Seq("sbt", 
          "-Dsbt.global.base="+base.getAbsolutePath,
          "-Dproject.build.results.file="+resultFile.getAbsolutePath,
          "-Dproject.build.deps.file="+depsFile.getAbsolutePath,
          "-Dsbt.repository.config="+repoFile.getAbsolutePath,
          "-Dproject.build.publish.repo="+dependencies.localRepo.getAbsolutePath,
          "-Dsbt.override.build.repos=true",
          "-Dsbt.version="+SbtConfig.sbtVersion,
          "-sbt-version",
          SbtConfig.sbtVersion,
          "-Dsbt.log.noformat=true",
          "dsbt-build"), Some(project)) ! log match {
        case 0 => log.success("Build succesful")
        case n => 
          log.err("Failure to run sbt build ("+project.getAbsolutePath+")!  Error code: " + n)
          sys.error("Failure to run sbt build("+project.getAbsolutePath+")!  Error code: " + n)
      }
      
      (BuildArtifactsParser parseMetaFile resultFile getOrElse
        sys.error("Failed to generate or load build results!"))
    }
  }
}