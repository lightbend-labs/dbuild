package distributed
package support
package sbt

import project.BuildSystem
import project.model._
import _root_.sbt.Path._
import logging.Logger
import distributed.project.model.SbtExtraConfig
import _root_.java.io.File

/** Implementation of the SBT build system. */
class SbtBuildSystem(workingDir: File = local.ProjectDirs.builddir) extends BuildSystem {
  val name: String = "sbt"  
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(buildBase / "runner")
  final val extractor = new SbtRunner(buildBase / "extractor")
  
  // expandDefaults is always called as first step; diagnostic should go here.
  // the others can assume the 'extra' field is of the right type (and will
  // fail with a match error (internal error) if it is not.
  override def expandDefaults(config: ProjectBuildConfig): ProjectBuildConfig = {
    val sc = sbtExpandConfig(config)
    config.copy(extra=Some(sc))
  }
    
  private def sbtExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => SbtExtraConfig(sbtVersion = Defaults.sbtVersion) // pick default values
    case Some(ec:SbtExtraConfig) => {
      if (ec.sbtVersion == "")
        ec.copy(sbtVersion = Defaults.sbtVersion)
      else
        ec
    }
    case _ => throw new Exception("Internal error: sbt build config options are the wrong type in project \""+config.name+"\". Please report")
  }
  
  override def projectDbuildDir(baseDir: File, config: ProjectBuildConfig): File = {
    val Some(ec:SbtExtraConfig) = config.extra
    projectDir(baseDir, ec) / ".dbuild"
  }

  private def projectDir(baseDir: _root_.java.io.File, ec: SbtExtraConfig): _root_.java.io.File = {
    val projectDir=if(ec.directory.isEmpty) baseDir else baseDir / ec.directory
    // sanity check, in case "directory" is something like "../xyz" or "/xyz/..."
    if (!(projectDir.getAbsolutePath().startsWith(baseDir.getAbsolutePath())))
        sys.error("The specified subdirectory \""+ec.directory+"\" does not seem not be a subdir of the project directory")
    projectDir
  }

  def extractDependencies(config: ProjectBuildConfig, baseDir: File, log: Logger): ExtractedBuildMeta = {
    val Some(ec:SbtExtraConfig) = config.extra
    val projDir = projectDir(baseDir, ec)
    SbtExtractor.extractMetaData(extractor)(projDir, ec, log)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, log: logging.Logger): BuildArtifacts = {
    val Some(sc:SbtExtraConfig) = project.config.extra
    val name = project.config.name
    // TODO - Does this work correctly?
    val pdir = if(sc.directory.isEmpty) dir else dir / sc.directory
    val config = SbtBuildConfig(sc, info)
    SbtBuilder.buildSbtProject(runner)(pdir, config, log)
  }

}
