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
  
  def writeRepoFile(config: File, repo: File): Unit =
    Repositories.writeRepoFile(config, "build-local" -> repo.toURI.toASCIIString)

  def buildSbtProject(runner: SbtRunner)(project: File, config: SbtBuildConfig, log: logging.Logger): BuildArtifacts = {    
    IO.withTemporaryDirectory { tmpDir => 
      val resultFile = tmpDir / "results.dsbt"
      // TODO - Where should depsfile + repo file be?  
      // For debugging/reproducing issues, we're putting them in a local directory for now.
      val dsbtDir = project / ".dsbt"
      val depsFile = dsbtDir / "deps.dsbt"
      val repoFile = dsbtDir / "repositories"
      // We need a new ivy cache to ensure no corruption of minors (or projects)
      val ivyCache = dsbtDir / "ivy2"
      IO.write(depsFile, makeConfigString(config))
      writeRepoFile(repoFile, config.info.arts.localRepo)
      log.debug("Runing SBT build in " + project + " with depsFile " + depsFile)
      runner.run(
        projectDir = project,
        log = log,
        javaProps = Map(
            "sbt.repository.config" -> repoFile.getAbsolutePath,
            "project.build.results.file" -> resultFile.getAbsolutePath,
            "project.build.deps.file" -> depsFile.getAbsolutePath,
            "sbt.ivy.home" -> ivyCache.getAbsolutePath)
      )("dsbt-build")      
      (parseFileInto[BuildArtifacts](resultFile) getOrElse
        sys.error("Failed to generate or load build results!"))
    }
  }
}