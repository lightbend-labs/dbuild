package distributed
package support
package sbt

import project.model._
import _root_.sbt.Path._
import logging.Logger
import distributed.project.model.SbtExtraConfig
import _root_.java.io.File
import distributed.repo.core.{Defaults,GlobalDirs}
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner
import distributed.project.{ BuildSystem, BuildData }

/** Implementation of the SBT build system. */
class SbtBuildSystem(repos:List[xsbti.Repository], workingDir:File, debug: Boolean) extends BuildSystemCore {
  val name: String = "sbt"
  type ExtraType = SbtExtraConfig
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(repos, buildBase / "runner", debug)
  final val extractor = new SbtRunner(repos, buildBase / "extractor", debug)
  
  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    // no 'extra' section in an sbt project? pick default values from ExtraOptions
    case None => SbtExtraConfig(sbtVersion = Some(defaults.sbtVersion),
      extractionVersion = Some(defaults.extractionVersion))
    // an 'extra' section is present. One or both of 'sbtVersion' and 'compiler' might be missing.
    case Some(ec: SbtExtraConfig) => {
      val sbtVer = ec.sbtVersion match {
        case None => defaults.sbtVersion
        case Some(v) => v
      }

      val extrVer = ec.extractionVersion match {
        case None => defaults.extractionVersion
        case Some(c) => c
      }
      val allCommands = defaults.sbtCommands ++ ec.commands
      ec.copy(sbtVersion = Some(sbtVer), extractionVersion = Some(extrVer), commands = allCommands)
    }
    case _ => throw new Exception("Internal error: sbt build config options have the wrong type. Please report.")
  }

  private def projectDir(baseDir: _root_.java.io.File, ec: SbtExtraConfig): _root_.java.io.File = {
    val projectDir=if(ec.directory.isEmpty) baseDir else baseDir / ec.directory
    // sanity check, in case "directory" is something like "../xyz" or "/xyz/..."
    if (!(projectDir.getAbsolutePath().startsWith(baseDir.getAbsolutePath())))
        sys.error("The specified subdirectory \""+ec.directory+"\" does not seem to be a subdir of the project directory")
    projectDir
  }

  def extractDependencies(config: ExtractionConfig, baseDir: File, extr: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val ec = config.extra[ExtraType]
    val projDir = projectDir(baseDir, ec)
    SbtExtractor.extractMetaData(extractor)(projDir, ec, log, debug)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, localBuildRunner: LocalBuildRunner,
      buildData: BuildData): BuildArtifactsOut = {
    val ec = project.extra[ExtraType]
    val name = project.config.name
    // TODO - Does this work correctly?
    val pdir = if(ec.directory.isEmpty) dir else dir / ec.directory
    val config = SbtBuildConfig(ec, project.config.crossVersion getOrElse sys.error("Internal error: crossVersion not expanded in runBuild."), info)
    SbtBuilder.buildSbtProject(repos, runner)(pdir, config, buildData.log, buildData.debug)
  }

}
