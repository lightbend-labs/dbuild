package com.typesafe.dbuild.support.sbt

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.adapter.Adapter
import Adapter.{ IO, Path }
import Adapter.syntaxio._
import Path._
import _root_.java.io.File
import sys.process.Process
import com.typesafe.dbuild.model.Utils.{ readValue, writeValue }
import com.typesafe.dbuild.logging.Logger.logFullStackTrace
import com.fasterxml.jackson.annotation.JsonProperty
import com.typesafe.dbuild.model.SeqStringH._
import com.typesafe.dbuild.utils.TrackedProcessBuilder

/**
 * Input data to the dbuild sbt plugin
 */
case class ExtractionInput(
  projects: Seq[String],
  @JsonProperty("excluded-projects") excludedProjects: Seq[String],
  extractionScalaVersion: Option[String],
  debug: Boolean)

object SbtExtractor {

  // TODO - Better synchronize?
  def extractMetaData(repos: List[xsbti.Repository], runner: SbtRunner)(tracker: TrackedProcessBuilder,
    projectDir: File, extra: SbtExtraConfig, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    log.debug("Extracting dependencies of SBT build:")
    log.debug("  " + projectDir.getCanonicalPath())
    val scalaCompiler = extra.extractionVersion getOrElse
      sys.error("Internal error: \"compiler\" has not been expanded. Please report.")
    val scalaVersion = scalaCompiler match {
      case "standard" =>
        log.info("Using project's standard Scala version during extraction.")
        None
      case v =>
        log.info("Using Scala " + v + " during extraction.")
        Some(v)
    }

    // We need to set up all the plugins, and other changes to the build definitions that are necessary
    // in order for dbuild to operate.
    //
    // For extraction, we need to find out the number of levels "n" and place:
    // - in levels 0..n, silence Ivy (if requested)
    // - in levels 0..n-1, add an onLoad() that performs the actual dependency extraction for that level
    // - in levels 1..n, add a suitable addSbtPlugin()
    // - create in levels 0..n-1 a work directory (".dbuild", SbtRunner.dbuildSbtDirName to be precise)
    // in order to house the extraction output where each level is the main dir, followed by "/project" n times.
    //
    // We know that projectDir exists, and that it contains no extraneous files (as per the resolve() contract)
    // So:
    val sbtSettings = extra.settings.expand
    // We might have injected additional settings; they also contribute to the number of levels.
    // This must be identical to the one in SbtBuilder.prepareRewireFilesAndDirs().
    val levels = SbtRunner.buildLevels(projectDir) max sbtSettings.size
    log.debug("This sbt build has definitions on " + levels + " levels.")
    // create the .dbuild dirs
    SbtRunner.prepDBuildDirs(projectDir, levels)
    //
    // We need a suitable .sbt file in each directory. Some definitions go only in the first one,
    // some in all the middle ones, and some only in the last one.
    val allButLast = SbtRunner.onLoad("com.typesafe.dbuild.plugin.DependencyAnalysis.printCmd(state,previousOnLoad)")
    val allButFirst = SbtRunner.addDBuildPlugin
    val all = SbtRunner.ivyQuiet(debug)
    // Create a tuple for (first, middle, last) possible contents 
    val (first, middle, last) = (allButLast + all, allButLast + allButFirst + all, allButFirst + all)
    // this is the sequence of contents of the various files, sans sbtSettings 
    val sbtFiles = first +: Stream.fill(levels - 1)(middle) :+ last
    // to each file, prepend the additional settings
    val settings = (extra.settings.expand.map { _.map { _ + "\n\n" }.mkString }).toStream ++ Stream.continually("")
    val finalSbtFiles = (settings zip sbtFiles) map { case (a, b) => a + b }
    // Let's place them in the required dirs
    SbtRunner.writeSbtFiles(projectDir, finalSbtFiles, log, debug)

    import SbtRunner.SbtFileNames._
    // This is for the first level only
    val inputDataFirst = ExtractionInput(extra.projects, extra.exclude, scalaVersion, debug)
    val inputDataAll = inputDataFirst +: Stream.fill(levels - 1)(ExtractionInput(Seq.empty, Seq.empty, None, debug))
    SbtRunner.placeInputFiles(projectDir, extractionInputFileName, inputDataAll, log, debug)

    // The "repositories" file used to be common for all sbt invocations, defined in SbtRunner.
    // That would have led to problems in the future, as simultaneous dbuild invocation would have
    // overwritten each other's repositories set. Instead, we create a repositories file in each
    // project build/extraction dir. That makes it also easier to implement the "dbuild --checkout" feature.
    val dbuildSbtDir = projectDir / dbuildSbtDirName
    val repoFile = dbuildSbtDir / repositoriesFileName
    SbtRunner.writeRepoFile(repos, repoFile)

    // NOTE: all of the extractions use the same global ivy cache, in ~/.dbuild/ivy2. This should be safe,
    // as rewiring is only done during building, and sbt locks the ivy cache. If snapshot resolution should
    // lead to problems, it is possible to redefine ivyPaths as an extra setting in the additional sbt file
    // that contains onLoad, following the model used during rewiring, so that each extraction (and possibly
    // each extraction level) uses a separate ivy cache. That seems overkill, however: the global one should be ok.
    runner.run(
      tracker = tracker,
      projectDir = projectDir,
      sbtVersion = extra.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
      log = log,
      javaProps = Map(
        // "sbt.override.build.repos" is defined in the default runner props (see SbtRunner)
        "sbt.repository.config" -> repoFile.getCanonicalPath
      ),
      extraArgs = extra.javaAllOptions)((Seq("")): _*) // no extraction command is invoked; all is done by OnLoad()

    ExtractedBuildMeta(SbtRunner.collectOutputFiles[ProjMeta](projectDir, extractionOutputFileName, levels, log, debug))
  }

}
