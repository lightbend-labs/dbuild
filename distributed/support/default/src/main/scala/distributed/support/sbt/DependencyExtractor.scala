package distributed
package support
package sbt

import project.model._
import _root_.sbt.{ IO, Path, PathExtra }
import Path._
import _root_.java.io.File
import sys.process.Process
import distributed.project.model.Utils.{ readValue, writeValue }
import distributed.logging.Logger.logFullStackTrace
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Input data to the dbuild sbt plugin
 */
case class ExtractionInput(
  projects: Seq[String],
  @JsonProperty("excluded-projects") excludedProjects: Seq[String],
  debug: Boolean)

object SbtExtractor {

  // TODO - Better synchronize?
  def extractMetaData(runner: SbtRunner)(projectDir: File, extra: SbtExtraConfig, log: logging.Logger, debug: Boolean): ExtractedBuildMeta = {
    log.debug("Extracting dependencies of SBT build:")
    log.debug("  " + projectDir.getCanonicalPath())
    val scalaCompiler = extra.extractionVersion getOrElse
      sys.error("Internal error: \"compiler\" has not been expanded. Please report.")
    val setScalaCommand: Seq[String] = scalaCompiler match {
      case "standard" =>
        log.info("Using project's standard Scala version during extraction.")
        Seq.empty
      case v =>
        log.info("Using Scala " + v + " during extraction.")
        Seq("set every scalaVersion := \"" + v + "\"")
    }

    // We need to set up all the plugins, and other changes to the build definitions that are necessary
    // in order for dbuild to operate.
    //
    // For extraction, we need to find out the number of levels "n" and place:
    // - in levels 0..n, silence Ivy (if requested)
    // - in levels 0..n-1, add an onLoad() that performs the actual dependency extraction for that level
    // - in levels 1..n, add a suitable addSbtPlugin()
    // - create in levels 0..n-1 a directory ".dbuild" in order to house the extraction output
    // where each level is the main dir, followed by "/project" n times.
    //
    // We know that projectDir exists, and that it contains no extranoues files (as per the resolve() contract)
    // So:
    val levels = SbtRunner.buildLevels(projectDir)
    log.debug("This sbt build has definitions on " + levels + " levels.")
    // create the .dbuild dirs
    SbtRunner.prepDBuildDirs(projectDir, levels)
    //
    // We need a suitable .sbt file in each directory. Some definitions go only in the first one,
    // some in all the middle ones, and some only in the last one.
    // Create a tuple for (first, middle, last) possible contents 
    def generateSbtFiles(allButLast: String, allButFirst: String, all: String): (String, String, String) =
      (allButLast + all, allButLast + allButFirst + all, allButFirst + all)

    val allButLast = SbtRunner.onLoad("""
          println("I am happy to report that I can transform the state in the dir "+
            ((sbt.Keys.baseDirectory in ThisBuild get Project.extract(state).structure.data) getOrElse "<unknown>"))
          com.typesafe.dbuild.DependencyAnalysis.printCmd(state)
          """)
    val allButFirst = SbtRunner.addDBuildPlugin
    val all = SbtRunner.ivyQuiet(debug)

    val (first, middle, last) = generateSbtFiles(allButLast, allButFirst, all)
    // this is the sequence of contents of the various files 
    val sbtFiles = first +: Stream.fill(levels - 1)(middle) :+ last
    // Let's place them in the required dirs
    SbtRunner.writeSbtFiles(projectDir, sbtFiles, log, debug)

    import SbtRunner.FileNames._
    val inputFile = projectDir / dbuildSbtFileName / extractionInputFileName
    // This is for the first level only
    val inputDataFirst = ExtractionInput(extra.projects, extra.exclude, debug)
    val inputDataAll = inputDataFirst +: Stream.fill(levels - 1)(ExtractionInput(Seq.empty, Seq.empty, debug))
    SbtRunner.placeInputFiles(projectDir, extractionInputFileName, inputDataAll, log, debug)

    runner.run(
      projectDir = projectDir,
      sbtVersion = extra.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
      log = log,
      extraArgs = extra.options)((extra.commands ++ setScalaCommand).:+(""): _*)

    ExtractedBuildMeta(SbtRunner.collectOutputFiles[ProjMeta](projectDir, extractionOutputFileName, levels, log, debug))
  }

}
