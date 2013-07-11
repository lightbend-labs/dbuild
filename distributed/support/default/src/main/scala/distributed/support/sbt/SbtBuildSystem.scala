package distributed
package support
package sbt

import project.BuildSystem
import project.model._
import _root_.sbt.Path._
import logging.Logger
import distributed.project.model.SbtExtraConfig
import _root_.java.io.File
import distributed.repo.core.{Defaults,ProjectDirs}

/** Implementation of the SBT build system. */
class SbtBuildSystem(repos:List[xsbti.Repository], workingDir:File = ProjectDirs.builddir) extends BuildSystem {
  val name: String = "sbt"  
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(repos, buildBase / "runner")
  final val extractor = new SbtRunner(repos, buildBase / "extractor")
  
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
    val ec = sbtExpandConfig(config)
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
    val ec = sbtExpandConfig(config)
    val projDir = projectDir(baseDir, ec)
    SbtExtractor.extractMetaData(extractor)(projDir, ec, log)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, log: logging.Logger): BuildArtifactsOut = {
    val ec = sbtExpandConfig(project.config)
    val name = project.config.name
    // TODO - Does this work correctly?
    val pdir = if(ec.directory.isEmpty) dir else dir / ec.directory
    val config = SbtBuildConfig(ec, info)
    SbtBuilder.buildSbtProject(repos, runner)(pdir, config, log)
  }

}
