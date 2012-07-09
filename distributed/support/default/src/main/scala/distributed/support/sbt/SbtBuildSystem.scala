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
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(buildBase / "runner")
  final val extractor = new SbtRunner(buildBase / "extractor")
  
  def extractDependencies(config: BuildConfig, dir: File, log: Logger): ExtractedBuildMeta =
    if(config.directory.isEmpty) SbtExtractor.extractMetaData(extractor)(dir, log)
    else SbtExtractor.extractMetaData(extractor)(new File(dir, config.directory), log)

  def runBuild(project: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts =
    SbtBuilder.buildSbtProject(runner)(dir, dependencies, log)
}