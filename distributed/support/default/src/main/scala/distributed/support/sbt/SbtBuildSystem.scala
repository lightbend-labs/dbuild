package distributed
package support
package sbt

import project.BuildSystem
import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import logging.Logger

/** Implementation of the SBT build system. */
class SbtBuildSystem(workingDir: File = local.ProjectDirs.builddir) extends BuildSystem {
  val name: String = "sbt"  
  val buildBase = workingDir / "sbt-build-root"
  val extractbase = workingDir / "sbt-extract-root"
  
  def extractDependencies(config: BuildConfig, dir: File, log: Logger): ExtractedBuildMeta =
    if(config.directory.isEmpty) SbtExtractor.extractMetaData(dir, extractbase, log)
    else SbtExtractor.extractMetaData(new File(dir, config.directory), extractbase, log)

  def runBuild(project: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts =
    SbtBuilder.buildSbtProject(dir, dependencies, buildBase, log)
}