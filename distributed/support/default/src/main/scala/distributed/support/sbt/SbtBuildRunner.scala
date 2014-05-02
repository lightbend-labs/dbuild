package distributed
package support
package sbt

import project.model._
import _root_.sbt.{ IO, Path, PathExtra }
import Path._
import _root_.java.io.File
import sys.process.Process
import distributed.project.model.Utils.{ writeValue, readValue }
import distributed.logging.Logger.logFullStackTrace
import distributed.project.build.BuildDirs._
import distributed.support.sbt.SbtRunner.SbtFileNames._
import distributed.support.sbt.SbtRunner.{ sbtIvyCache, buildArtsFile }

/**
 * Rewiring a level needs the information contained in RewireInput:
 *
 * @param in The BuildArtifactsIn, comprising: a description of the artifacts contained in the local repo,
 *        plus the directory where those artifacts have been rematerialized
 * @param subproj The sbt subprojects that will be rebuilt in this level (empty means all subprojects)
 * @param crossVersion The cross version selector for the artifacts that will result from the
 *                     rebuilding of this level. It is not really relevant for the levels other than
 *                     the first one, but it still controls whether missing dependencies will be
 *                     detected or not while rewiring.
 */
// TODO: split crossVersion into crossVersion and detectMissing.
case class RewireInput(in: BuildArtifactsIn, subproj: Seq[String], crossVersion: String, debug: Boolean)
/**
 * Input to generateArtifacts()
 */
case class GenerateArtifactsInput(info: BuildInput, runTests: Boolean, /* not fully supported */ measurePerformance: Boolean, debug: Boolean)

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {

  def writeRepoFile(repos: List[xsbti.Repository], config: File, repo: File): Unit =
    Repositories.writeRepoFile(repos, config, "build-local" -> repo.toURI.toASCIIString)

  def buildSbtProject(repos: List[xsbti.Repository], runner: SbtRunner)(projectDir: File, config: SbtBuildConfig,
    log: logging.Logger, debug: Boolean): BuildArtifactsOut = {

    // we do the rewiring on each level using onLoad; we generate the artifacts at the end
    val levels = SbtRunner.buildLevels(projectDir)
    // create the .dbuild dirs in each level (we will use it to store the ivy cache, and other info)
    SbtRunner.prepDBuildDirs(projectDir, levels)

    // preparation of the sbt files used to drive rewiring, via onLoad
    def generateSbtFiles(allButLast: String, allButFirst: String, all: String): (String, String, String) =
      (allButLast + all, allButLast + allButFirst + all, allButFirst + all)
    val allButLast = SbtRunner.onLoad("com.typesafe.dbuild.DistributedRunner.rewire(state, previousOnLoad)")
    val allButFirst = SbtRunner.addDBuildPlugin
    val all = SbtRunner.ivyQuiet(debug)
    val (first, middle, last) = generateSbtFiles(allButLast, allButFirst, all)
    val sbtFiles = first +: Stream.fill(levels - 1)(middle) :+ last
    SbtRunner.writeSbtFiles(projectDir, sbtFiles, log, debug)

    // now, let's prepare and place the input data to rewiring
    val ins = config.info.artifacts.materialized
    val subprojs = config.info.subproj
    
    // The defaults are: "disabled","standard","standard"....
    val defaultCrossVersions = CrossVersionsDefaults.defaults
    val crossVersionStream = config.crossVersion.toStream ++ defaultCrossVersions.drop(config.crossVersion.length)
    // This is for the first level only
    val inputDataFirst = RewireInput(ins.head, subprojs.head, crossVersionStream.head, debug)
    val inputDataRest = (ins.tail, subprojs.tail, crossVersionStream.tail).zipped map {
      case (in, subproj, cross) =>
        RewireInput(in, subproj, cross, debug)
    }
    val inputDataAll = inputDataFirst +: inputDataRest
    SbtRunner.placeInputFiles(projectDir, rewireInputFileName, inputDataAll, log, debug)

    // preparation of the input data to generateArtifacts()
    // This is for the first level only
    val buildIn = GenerateArtifactsInput(config.info,
        measurePerformance = config.config.measurePerformance,
        runTests = config.config.runTests,
        debug = debug)
    SbtRunner.placeGenArtsInputFile(projectDir, buildIn)

    // SHOULD BE DISABLED also disabled (see below)
    val dbuildSbtDir = projectDir / dbuildSbtDirName
    val ivyCache = dbuildSbtDir / "elsewhere" / "ivy2"

    runner.run(
      projectDir = projectDir,
      sbtVersion = config.config.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
      log = log,
      javaProps = Map(
        "sbt.ivy.home" -> ivyCache.getAbsolutePath
      ),
      /* NOTE: New in dbuild 0.9: commands are run AFTER rewiring and BEFORE building. */ 
      extraArgs = config.config.options)((config.config.commands).:+("dbuild-build"): _*)
    return readValue[BuildArtifactsOut](buildArtsFile(projectDir))
  }
}