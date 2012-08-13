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
  
  
  def sbtConfig(config: BuildConfig): SbtConfig =
    config.extra match {
      case SbtConfig.Configured(sc) => sc
      case _ => sys.error("SBT is misconfigured: " + config)
    }
  
  def extractDependencies(config: BuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    val sc = sbtConfig(config)
    if(sc.directory.isEmpty) SbtExtractor.extractMetaData(extractor)(dir, log)
    else SbtExtractor.extractMetaData(extractor)(new File(dir, sc.directory), log)
  }

  def runBuild(project: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    val sc = sbtConfig(project.config)
    // TODO - Does this work correctly?
    val pdir = if(sc.directory.isEmpty) dir else dir / sc.directory
    SbtBuilder.buildSbtProject(runner)(pdir, SbtBuildConfig(sc, dependencies), log)
  }
}