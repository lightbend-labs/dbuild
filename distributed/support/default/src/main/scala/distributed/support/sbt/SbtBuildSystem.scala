package distributed
package support
package sbt

import project.model._
import _root_.sbt.Path._
import logging.Logger
import distributed.project.model.SbtExtraConfig
import _root_.java.io.File
import distributed.repo.core.{Defaults,ProjectDirs}
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner

/** Implementation of the SBT build system. */
class SbtBuildSystem(repos:List[xsbti.Repository], workingDir:File) extends BuildSystemCore {
  val name: String = "sbt"  
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(repos, buildBase / "runner")
  final val extractor = new SbtRunner(repos, buildBase / "extractor")
  
  private def sbtExpandConfig(config: ProjectBuildConfig, buildOptions:BuildOptions) = config.extra match {
    case None => SbtExtraConfig(sbtVersion = Some(buildOptions.sbtVersion)) // pick default values
    case Some(ec:SbtExtraConfig) => {
      ec.sbtVersion match {
        case None => ec.copy(sbtVersion = Some(buildOptions.sbtVersion))
        case Some(sbtVer) => ec
      }
    }
    case _ => throw new Exception("Internal error: sbt build config options are the wrong type in project \""+config.name+"\". Please report")
  }

  override def projectDbuildDir(baseDir: File, config: RepeatableProjectBuild): File = {
    val ec = sbtExpandConfig(config.config, config.buildOptions)
    projectDir(baseDir, ec) / ".dbuild"
  }

  private def projectDir(baseDir: _root_.java.io.File, ec: SbtExtraConfig): _root_.java.io.File = {
    val projectDir=if(ec.directory.isEmpty) baseDir else baseDir / ec.directory
    // sanity check, in case "directory" is something like "../xyz" or "/xyz/..."
    if (!(projectDir.getAbsolutePath().startsWith(baseDir.getAbsolutePath())))
        sys.error("The specified subdirectory \""+ec.directory+"\" does not seem not be a subdir of the project directory")
    projectDir
  }

  def extractDependencies(config: ExtractionConfig, baseDir: File, extr: Extractor, log: Logger): ExtractedBuildMeta = {
    val ec = sbtExpandConfig(config.buildConfig, config.buildOptions)
    val projDir = projectDir(baseDir, ec)
    SbtExtractor.extractMetaData(extractor)(projDir, ec, log)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut = {
    val ec = sbtExpandConfig(project.config, project.buildOptions)
    val name = project.config.name
    // TODO - Does this work correctly?
    val pdir = if(ec.directory.isEmpty) dir else dir / ec.directory
    val config = SbtBuildConfig(ec, info, project.buildOptions)
    SbtBuilder.buildSbtProject(repos, runner)(pdir, config, log)
  }

}
