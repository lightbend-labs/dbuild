package com.typesafe.dbuild.support.sbt

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import _root_.sbt.{ IO, Path, PathExtra }
import Path._
import _root_.java.io.File
import sys.process.Process
import com.typesafe.dbuild.model.Utils.{ writeValue, readValue }
import com.typesafe.dbuild.logging.Logger.logFullStackTrace
import com.typesafe.dbuild.project.build.BuildDirs._
import com.typesafe.dbuild.support.sbt.SbtRunner.SbtFileNames._
import com.typesafe.dbuild.support.sbt.SbtRunner.{ sbtIvyCache, buildArtsFile }
import com.typesafe.dbuild.model.SeqSeqString
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
case class RewireInput(in: BuildArtifactsIn, subproj: Seq[String],
  crossVersion: String, checkMissing: Boolean, debug: Boolean)
/**
 * Input to generateArtifacts()
 */
case class GenerateArtifactsInput(info: BuildInput, runTests: Boolean, debug: Boolean)

object SbtBuilder {

  // If customProcess is not None, the resulting sbt command line will be prepared and then
  // passed to customProcess, rather than to the regular Process() in SbtRunner. This feature
  // is used by "dbuild checkout".
  def buildSbtProject(repos: List[xsbti.Repository], runner: SbtRunner)(projectDir: File, config: SbtBuildConfig,
    log: Logger, debug: Boolean, customProcess: Option[(File, Logger, File, Seq[String]) => Unit] = None,
    targetCommands: Seq[String] = Seq("dbuild-build"))(): Unit = {

    // everything needed for the automatic rewiring, driven by
    // the onLoad() calls on each level
    // now, let's prepare and place the input data to rewiring
    val arts = config.info.artifacts
    val subprojs = config.info.subproj
    val crossVers = config.crossVersion
    val checkMissing = config.checkMissing
    val settings = config.config.settings.expand
    prepareRewireFilesAndDirs(projectDir, arts, subprojs, crossVers, checkMissing, settings, log, debug)

    // preparation of the input data to generateArtifacts()
    // This is for the first level only
    val buildIn = GenerateArtifactsInput(config.info,
      runTests = config.config.runTests,
      debug = debug)
    SbtRunner.placeGenArtsInputFile(projectDir, buildIn)

    // this "ivyCache" is not used for all the levels in which rewiring takes place; for those levels
    // the "onLoad" sets the ivy cache to a level-specific location. However for the topmost levels,
    // in which there is no rewiring or setting adjustments, the ivy cache in use will be the one
    // specified here. No rematerialized artifacts will end up there, hence no possible collisions.
    val dbuildSbtDir = projectDir / dbuildSbtDirName
    val topIvyCache = dbuildSbtDir / "topIvy" / "ivy2"
    // the top levels also do not get the repositories adjustment offered by FixResolvers2() in
    // DBuildRunner. However, all levels rely on the "repositories" file written here:
    val repoFile = dbuildSbtDir / repositoriesFileName
    val baseRematerializedRepo = localRepos(projectDir).head
    SbtRunner.writeRepoFile(repos, repoFile, "build-local" -> baseRematerializedRepo.toURI.toASCIIString)

    runner.run(
      projectDir = projectDir,
      sbtVersion = config.config.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
      log = log,
      javaProps = Map(
        "sbt.ivy.home" -> topIvyCache.getCanonicalPath,
        // "sbt.override.build.repos" is defined in the default runner props (see SbtRunner)
        "sbt.repository.config" -> repoFile.getCanonicalPath
      ),
      /* NOTE: New in dbuild 0.9: commands are run AFTER rewiring and BEFORE building. */
      extraArgs = config.config.options,
      process = customProcess)(config.config.commands ++ targetCommands: _*)
  }

  def prepareRewireFilesAndDirs(projectDir: File, artifacts: BuildArtifactsInMulti,
    subprojs: Seq[Seq[String]], crossVers: Seq[String], checkMiss: Seq[Boolean], sbtSettings: Seq[Seq[String]],
    log: _root_.sbt.Logger, debug: Boolean): Unit = {
    // we do the rewiring on each level using onLoad; we generate the artifacts at the end
    // Note:  Because the user could configure settings/projects for build levels which do not have configuration,
    //        we must figure out if there is configuration which will be written to a given build level.
    // TODO - We may just need to check sbtSettings.size + the detected build levels...

    // We might have injected additional settings; they also contribute to the number of levels.
    // This must be identical to the one in SbtExtractor.extractMetaData().
    val levels = SbtRunner.buildLevels(projectDir) max sbtSettings.size
    // create the .dbuild dirs in each level (we will use it to store the ivy cache, and other info)
    SbtRunner.prepDBuildDirs(projectDir, levels)

    // preparation of the sbt files used to drive rewiring, via onLoad
    val onlyMiddle = SbtRunner.onLoad("com.typesafe.dbuild.plugin.DBuildRunner.rewire(state, previousOnLoad)")
    val onlyFirst = SbtRunner.onLoad("com.typesafe.dbuild.plugin.DBuildRunner.rewire(state, previousOnLoad, fixPublishSettings=true)")
    val allButFirst = SbtRunner.addDBuildPlugin
    val all = SbtRunner.ivyQuiet(debug)
    val (first, middle, last) = (onlyFirst + all, onlyMiddle + allButFirst + all, allButFirst + all)
    val sbtFiles = first +: Stream.fill(levels - 1)(middle) :+ last
    // to each file, prepend the additional settings
    val settings = (sbtSettings.map { _.map { _ + "\n\n" }.mkString }).toStream ++ Stream.continually("")
    val finalSbtFiles = (settings zip sbtFiles) map { case (a, b) => a + b }
    SbtRunner.writeSbtFiles(projectDir, finalSbtFiles, log, debug)

    // now, let's prepare and place the input data to rewiring
    val ins = artifacts.materialized
    // The defaults are: "disabled","standard","standard"....
    val defaultCrossVersions = CrossVersionsDefaults.defaults
    val crossVersionStream = crossVers.toStream ++ defaultCrossVersions.drop(crossVers.length)
    val checkMissingStream = checkMiss.toStream ++ crossVersionStream.drop(checkMiss.length).map {
      // The default value for checkMissing is true, except if crossVersion is "standard", as
      // we are unable to perform the check in that case.
      _ != "standard"
    }
    // .zipped works on three elements at most, hence the nesting
    val inputDataAll = ((ins, subprojs).zipped, crossVersionStream, checkMissingStream).zipped map {
      case ((in, subproj), cross, checkMissing) =>
        RewireInput(in, subproj, cross, checkMissing, debug)
    }
    SbtRunner.placeInputFiles(projectDir, rewireInputFileName, inputDataAll.toSeq, log, debug)
  }
}