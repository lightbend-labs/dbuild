package com.typesafe.dbuild.support.sbt

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.adapter.Adapter.Path._
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.model.SbtExtraConfig
import _root_.java.io.File
import com.typesafe.dbuild.adapter.Defaults
import com.typesafe.dbuild.adapter.Adapter.syntaxio._
import com.typesafe.dbuild.repo.core.GlobalDirs
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.model.Utils.{ writeValue, readValue }
import com.typesafe.dbuild.support.sbt.SbtRunner.{ sbtIvyCache, buildArtsFile }

/** Implementation of the SBT build system. */
class SbtBuildSystem(repos: List[xsbti.Repository], workingDir: File, debug: Boolean) extends BuildSystemCore {
  val name: String = "sbt"
  type ExtraType = SbtExtraConfig
  // TODO - Different runner for extracting vs. building?
  final val buildBase = workingDir / "sbt-base-dir"
  final val runner = new SbtRunner(repos, buildBase / "runner", debug)
  final val extractor = new SbtRunner(repos, buildBase / "extractor", debug)

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = {
    val globalJavaOpts: SeqString = defaults.sbtJavaOptions getOrElse SbtRunner.defaultJavaArgs
    extra match {
      //
      // Note that "javaAllOptions" in the "extra" section of an sbt-based project is not documented
      // (and has a special non-guessable name): it is used to receive the project-specific
      // combined list of the global ("build"-level) "sbt-java-options" section, plus the local
      // "options" (project-specific) which are appended to the global ones.
      // The roles of both "options" and "sbt-java-options" end after extraction; they dissolve
      // into "javaAllOptions", and are no longer used afterwards.
      //
      // No 'extra' section in an sbt project? pick default values from ExtraOptions
      case None => SbtExtraConfig(sbtVersion = Some(defaults.sbtVersion),
        extractionVersion = Some(defaults.extractionVersion),
        commands = defaults.sbtCommands,
        postCommands = defaults.sbtPostCommands,
        javaAllOptions = globalJavaOpts,
        options = Seq.empty,
        settings = defaults.sbtSettings)
      //
      // An 'extra' section is present. One or both of 'sbtVersion' and 'compiler' might be missing.
      //
      case Some(ec: SbtExtraConfig) => {
        val sbtVer = ec.sbtVersion match {
          case None => defaults.sbtVersion
          case Some(v) => v
        }
        val extrVer = ec.extractionVersion match {
          case None => defaults.extractionVersion
          case Some(c) => c
        }
        // It might already be non-empty in case we are re-building a repeatable config. In that case, take the existing value
        val javaAllOpts: SeqString = if (ec.javaAllOptions.nonEmpty)
          ec.javaAllOptions
        else
          globalJavaOpts ++ ec.options
        val allCommands = defaults.sbtCommands ++ ec.commands
        val allPostCommands = defaults.sbtPostCommands ++ ec.postCommands
        // The two places in which settings can be found,
        // defaults.sbtSettings and ec.settings, are lists that may be of different sizes,
        // where each element corresponds to a level. The elements are lists, and we need
        // to concatenate pairwise the two lists, up to the max of the two sizes.
        val allSettings = defaults.sbtSettings.expand.zipAll(ec.settings.expand, Seq.empty, Seq.empty).
          map { case (a, b) => (a ++ b): SeqString }
        ec.copy(sbtVersion = Some(sbtVer), extractionVersion = Some(extrVer), commands = allCommands,
          javaAllOptions = javaAllOpts, options = Seq.empty, postCommands = allPostCommands, settings = allSettings)
      }
      case _ => throw new Exception("Internal error: sbt build config options have the wrong type. Please report.")
    }
  }

  def extractDependencies(config: ExtractionConfig, baseDir: File, extr: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val ec = config.extra[ExtraType]
    val projDir = SbtBuildSystem.projectDir(baseDir, ec)
    SbtExtractor.extractMetaData(repos, extractor)(projDir, ec, log, debug)
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, info: BuildInput, localBuildRunner: LocalBuildRunner,
    buildData: BuildData): BuildArtifactsOut = {
    val ec = project.extra[ExtraType]
    val name = project.config.name
    val projDir = SbtBuildSystem.projectDir(dir, ec)
    val config = SbtBuildConfig(ec, project.config.crossVersion getOrElse sys.error("Internal error: crossVersion not expanded in runBuild."),
      project.config.checkMissing getOrElse sys.error("Internal error: checkMissing not expanded in runBuild."), info)
    SbtBuilder.buildSbtProject(repos, runner)(projDir, config, buildData.log, buildData.debug)
    readValue[BuildArtifactsOut](buildArtsFile(projDir))
  }
}

object SbtBuildSystem {
  def projectDir(baseDir: _root_.java.io.File, ec: SbtExtraConfig): _root_.java.io.File = {
    val projectDir = if (ec.directory.isEmpty) baseDir else baseDir / ec.directory
    // sanity check, in case "directory" is something like "../xyz" or "/xyz/..."
    if (!(projectDir.getCanonicalPath().startsWith(baseDir.getCanonicalPath())))
      sys.error("The specified subdirectory \"" + ec.directory + "\" does not seem to be a subdir of the project directory")
    projectDir
  }
}
