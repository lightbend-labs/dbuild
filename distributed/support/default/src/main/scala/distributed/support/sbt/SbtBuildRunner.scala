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
import distributed.support.sbt.SbtRunner.sbtIvyCache

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
    // This is for the first level only
    val inputDataFirst = RewireInput(ins.head, subprojs.head, config.crossVersion, debug)
    val inputDataRest = (ins.tail zip subprojs.tail) map {
      case (in, subproj) =>
        RewireInput(in, subproj, "standard", debug)
    }
    val inputDataAll = inputDataFirst +: inputDataRest
    SbtRunner.placeInputFiles(projectDir, rewireInputFileName, inputDataAll, log, debug)

    // temporarily, to test rewiring (the final version should be identical, but the first level should
    // invoke first rewiring, then use the resulting state to perform the building proper)
    runner.run(
      projectDir = projectDir,
      sbtVersion = config.config.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
      log = log,
      extraArgs = config.config.options)((config.config.commands /* TODO: commands are ignored right now */).:+(""): _*)
Thread.sleep(1000)
      System.exit(0)
return BuildArtifactsOut(Seq.empty)
      
// At this point, all levels are rewired. I "just" need to build, and I should be a-ok.
      
      
      
      
    // da qui in giù, ancora da sistemare
    
    IO.withTemporaryDirectory { tmpDir =>
      // TODOTONI - This stuff is only of use during the eventual building. I'm still sorting out rewiring, for now.
      val resultFile = tmpDir / "results.dbuild"
      // TODO - Where should depsfile + repo file be?  
      // For debugging/reproducing issues, we're putting them in a local directory for now.
      val dbuildDir = projectDir / dbuildDirName
      val depsFile = dbuildDir / "deps.dbuild"
      // We need a new ivy cache to ensure no corruption of minors (or projects)
      IO.write(depsFile, writeValue(config))
      
      // These two lines should not be necessary, since the rewiring stage already adds the two
      // extra repositories (in fixResolvers2(). Consequently, the global "repositories" file should suffice.
      // That is paired with the setup of the property "sbt.repository.config", below.
//      val repoFile = dbuildDir / "repositories"
//      writeRepoFile(repos, repoFile, config.info.artifacts.localRepo)


// also disabled (see below)
//            val ivyCache = dbuildDir / "ivy2"

      log.debug("Runing SBT build in " + projectDir + " with depsFile " + depsFile)
      SbtRunner.silenceIvy(projectDir, log, debug)
      runner.run(
        projectDir = projectDir,
        sbtVersion = config.config.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
        log = log,
        javaProps = Map(
// see above
//          "sbt.repository.config" -> repoFile.getAbsolutePath,

// sbt.ivy.home is not set: the sbt launcher will use the standard ~/.dbuild/ivy/ during startup, to download/use
// the regular dbuild/sbt artifacts. The rewiring stage will patch ivy-paths once we are inside, from onLoad(),
// before any ivy resolution is made (we need to set a different cache for each level anyway). Cool!
//          "sbt.ivy.home" -> ivyCache.getAbsolutePath,

          "dbuild.project.build.results.file" -> resultFile.getAbsolutePath,
          "dbuild.project.build.deps.file" -> depsFile.getAbsolutePath),
        extraArgs = config.config.options
      )(config.config.commands.:+("dbuild-build"): _*)
      try readValue[BuildArtifactsOut](resultFile)
      catch {
        case e: Exception =>
          logFullStackTrace(log, e)
          sys.error("Failed to generate or load build results!")
      }
    }
  }
}