package distributed
package support
package sbt

import project.BuildSystem
import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import logging.Logger
import distributed.project.model.SbtExtraConfig

/** Implementation of the SBT build system. */
class SbtBuildSystem(workingDir: File = local.ProjectDirs.builddir) extends BuildSystem {
  val name: String = "sbt"  
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(buildBase / "runner")
  final val extractor = new SbtRunner(buildBase / "extractor")
  
  
  def sbtExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => SbtExtraConfig(sbtVersion = Defaults.sbtVersion) // pick default values
    case Some(ec:SbtExtraConfig) => {
      if (ec.sbtVersion == "")
        ec.copy(sbtVersion = Defaults.sbtVersion)
      else
        ec
    }
    case _ => throw new Exception("Internal error: sbt build config options are the wrong type. Please report")
  }
  
  def extractDependencies(config: ProjectBuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    val Some(sc:SbtExtraConfig) = config.extra
    if(sc.directory.isEmpty) SbtExtractor.extractMetaData(extractor)(dir, sc, log)
    else SbtExtractor.extractMetaData(extractor)(new File(dir, sc.directory), sc, log)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, log: logging.Logger): BuildArtifacts = {
    val Some(sc:SbtExtraConfig) = project.config.extra
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
