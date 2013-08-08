package distributed
package support
package sbt

import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import distributed.project.model.Utils.{writeValue,readValue}

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {
  
  def writeRepoFile(repos:List[xsbti.Repository], config: File, repo: File): Unit =
    Repositories.writeRepoFile(repos, config, "build-local" -> repo.toURI.toASCIIString)

  def buildSbtProject(repos:List[xsbti.Repository], runner: SbtRunner)(project: File, config: SbtBuildConfig, log: logging.Logger): BuildArtifactsOut = {    
    IO.withTemporaryDirectory { tmpDir => 
      val resultFile = tmpDir / "results.dbuild"
      // TODO - Where should depsfile + repo file be?  
      // For debugging/reproducing issues, we're putting them in a local directory for now.
      val dbuildDir = project / ".dbuild"
      val depsFile = dbuildDir / "deps.dbuild"
      val repoFile = dbuildDir / "repositories"
      // We need a new ivy cache to ensure no corruption of minors (or projects)
      val ivyCache = dbuildDir / "ivy2"
      IO.write(depsFile, writeValue(config))
      writeRepoFile(repos, repoFile, config.info.artifacts.localRepo)
      log.debug("Runing SBT build in " + project + " with depsFile " + depsFile)
      runner.run(
        projectDir = project,
        log = log,
        javaProps = Map(
            "sbt.repository.config" -> repoFile.getAbsolutePath,
            "dbuild.project.build.results.file" -> resultFile.getAbsolutePath,
            "dbuild.project.build.deps.file" -> depsFile.getAbsolutePath,
            "sbt.ivy.home" -> ivyCache.getAbsolutePath),
        extraArgs = config.config.options
      )(config.config.commands.:+("dbuild-build"):_*)
      try readValue[BuildArtifactsOut](resultFile)
      catch { case e:Exception =>
        e.printStackTrace
        sys.error("Failed to generate or load build results!")
      }
    }
  }
}