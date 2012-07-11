package distributed
package support
package sbt

import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import config.{makeConfigString, parseFileInto}

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {
  
  def writeRepoFile(config: File, repo: File): Unit = {
    val ivyPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
    val sb = new StringBuilder("[repositories]\n")
    sb append  "  local\n"
    sb append ("  ivy-build-local: file://%s, %s\n" format (repo.getAbsolutePath, ivyPattern))
    sb append ("  mvn-build-local: file://%s\n" format (repo.getAbsolutePath))
    sb append  "  maven-central\n"
    sb append  "  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots\n"
    sb append  "  java-annoying-cla-shtuff: http://download.java.net/maven/2/\n"
    sb append ("  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases\n")
    sb append ("  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, %s\n" format (ivyPattern))
    sb append ("  dbuild-snapshots: http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots, %s\n" format (ivyPattern))
    sb append ("  sbt-plugin-releases: http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases, %s\n" format (ivyPattern))
    // This is because the git plugin is too prevalent...
    sb append  "  jgit-repo: http://download.eclipse.org/jgit/maven\n"
    // TODO - Typesafe repositories? ... NAH
    IO.write(config, sb.toString)
  } 
    
  
  def buildSbtProject(runner: SbtRunner)(project: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {    
    IO.withTemporaryDirectory { tmpDir => 
      val resultFile = tmpDir / "results.dsbt"
      // TODO - Where should depsfile + repo file be?  
      // For debugging/reproducing issues, we're putting them in a local directory for now.
      val dsbtDir = project / ".dsbt"
      val depsFile = dsbtDir / "deps.dsbt"
      val repoFile = dsbtDir / "repositories"
      // We need a new ivy cache to ensure no corruption of minors (or projects)
      val ivyCache = dsbtDir / "ivy2"
      IO.write(depsFile, makeConfigString(dependencies))
      writeRepoFile(repoFile, dependencies.localRepo)
      log.debug("Runing SBT build in " + project + " with depsFile " + depsFile)
      runner.run(
        projectDir = project,
        log = log,
        javaProps = Map(
            "sbt.repository.config" -> repoFile.getAbsolutePath,
            "project.build.results.file" -> resultFile.getAbsolutePath,
            "project.build.deps.file" -> depsFile.getAbsolutePath,
            "sbt.override.build.repos" -> "true",
            "sbt.ivy.home" -> ivyCache.getAbsolutePath)
      )("dsbt-build")      
      (parseFileInto[BuildArtifacts](resultFile) getOrElse
        sys.error("Failed to generate or load build results!"))
    }
  }
}