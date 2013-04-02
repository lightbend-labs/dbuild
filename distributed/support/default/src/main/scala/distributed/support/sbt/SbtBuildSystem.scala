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
  
  
  def sbtExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => ExtraConfig(buildToolVersion = Defaults.sbtVersion) // pick default values
    case Some(ec) => {
      if (ec.buildToolVersion == "")
        ec.copy(buildToolVersion = Defaults.sbtVersion)
      else
        ec
    }
  }
  
  def extractDependencies(config: ProjectBuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    val Some(sc) = config.extra
    if(sc.directory.isEmpty) SbtExtractor.extractMetaData(extractor)(dir, log)
    else SbtExtractor.extractMetaData(extractor)(new File(dir, sc.directory), log)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, log: logging.Logger): BuildArtifacts = {
    val Some(sc) = project.config.extra
    val name = project.config.name
    // TODO - Does this work correctly?
    val pdir = if(sc.directory.isEmpty) dir else dir / sc.directory
    val config = SbtBuildConfig(sc, info)
    SbtBuilder.buildSbtProject(runner)(pdir, config, log)
  }

  override def expandDefaults(config: ProjectBuildConfig): ProjectBuildConfig = {
    val sc = sbtExpandConfig(config)
    config.copy(extra=Some(sc))
  }
}