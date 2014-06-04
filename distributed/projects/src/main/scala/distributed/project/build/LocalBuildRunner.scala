package distributed
package project
package build

import model._
import logging.Logger
import distributed.project.resolve.ProjectResolver
import Logger.prepareLogMsg
import java.io.File
import distributed.repo.core._
import sbt.Path._
import dependencies.Extractor
import distributed.project.cleanup.Recycling.{ updateTimeStamp, markSuccess }
import BuildDirs._

/**
 * This class encodes the logic to resolve a project and run its build given
 * a local repository, a resolver and a build runner.
 */
class LocalBuildRunner(builder: BuildRunner,
  val extractor: Extractor,
  val repository: Repository) {

  def checkCacheThenBuild(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome],
    buildData: BuildData): BuildOutcome = {
    val log = buildData.log
    try {
      try {
        val subArtifactsOut = LocalRepoHelper.getPublishedDeps(build.uuid, repository, log) // will throw exception if not in cache yet
        LocalRepoHelper.debugArtifactsInfo(subArtifactsOut, log)
        BuildUnchanged(build.config.name, children, BuildArtifactsOut(subArtifactsOut))
      } catch {
        case t: RepositoryException =>
          log.debug("Failed to resolve: " + build.uuid + " from " + build.config.name)
          //log.trace(t)
          BuildSuccess(build.config.name, children, runLocalBuild(target, build, outProjects,
            buildData))
      }
    } catch {
      case t =>
        BuildFailed(build.config.name, children, prepareLogMsg(log, t))
    }
  }

  def runLocalBuild(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project],
    buildData: BuildData): BuildArtifactsOut =
    useProjectUniqueBuildDir(build.config.name + "-" + build.uuid, target) { dir =>
      updateTimeStamp(dir)
      // extractor.resolver.resolve() only resolves the main URI,
      // extractor.dependencyExtractor.resolve() also resolves the nested ones, recursively
      // here we only resolve the ROOT project, as we will later call the runBuild()
      // of the build system, which in turn will call checkCacheThenBuild(), above, on all subprojects,
      // which will again call this method, thereby resolve()ing each project right before building it.
      val log = buildData.log
      log.info("Resolving: " + build.config.uri + " in directory: " + dir)
      extractor.resolver.resolve(build.config, dir, log)
      val (dependencies, version, writeRepo) = LocalBuildRunner.prepareDepsArtifacts(repository, build, outProjects, dir, log)
      log.debug("Running local build: " + build.config + " in directory: " + dir)
      LocalRepoHelper.publishProjectInfo(build, repository, log)
      if (build.depInfo.isEmpty) sys.error("Internal error: depInfo is empty!")
      val results = builder.runBuild(build, dir,
        BuildInput(dependencies, version, build.configAndExtracted.extracted.projInfo.map{_.subproj}, writeRepo, build.config.name), this, buildData)
      LocalRepoHelper.publishArtifactsInfo(build, results.results, writeRepo, repository, log)
      markSuccess(dir)
      results
    }
}
object LocalBuildRunner {
  // Also used by the implementation of "dbuild checkout", in Checkout.scala
  // Rematerializes the project's dependencies, and prepares some other build information,
  // prior to the actual build.
  // Returns: (dependencies, version, writeRepo)
  def prepareDepsArtifacts(repository: Repository, build: RepeatableProjectBuild, outProjects: Seq[Project], dir: File, log: logging.Logger): (BuildArtifactsInMulti, String, File) = {
    //
    val readRepos = localRepos(dir)
    val uuidGroups = build.depInfo map (_.dependencyUUIDs)
    val fromSpaces = build.configAndExtracted.getSpace.fromStream // one per uuidGroup
    val dependencies = LocalRepoHelper.getArtifactsFromUUIDs(log.info, repository, readRepos, uuidGroups, fromSpaces)
    val BuildArtifactsInMulti(artifactLocations) = dependencies
    // Special case: scala-compiler etc must have the same version number
    // as scala-library: projects that rely on scala-compiler as a dependency
    // (notably sbt) may need that.
    // Therefore, if we see in the list of artifactLocations a scala-library,
    // and we are compiling another library of the Scala distribution, we check
    // that the scala-library version matches the current one.
    // Technically, we can even borrow the scala-library version, since it depends
    // on the dependency embedded within the RepeatableProjectBuild. Therefore the build
    // remains strictly repeatable, even if the library version does not appear in the
    // original project configuration: if anything changes in the scala-library dependency,
    // this project will also get a new uuid.
    // TODO: can we work around this quirk in a cleaner manner?
    log.debug(build.toString)
    // inspect only the artifacts reloaded at the base level
    val baseArtifacts = (artifactLocations.headOption getOrElse sys.error("Internal error: zero artifacts levels.")).artifacts
    val scalaLib = baseArtifacts find { a =>
      a.info.organization == "org.scala-lang" && a.info.name == "scala-library"
    }
    val libVersion = scalaLib flatMap { lib =>
      if (outProjects exists { p: Project =>
        p.organization == "org.scala-lang" && p.name.startsWith("scala")
      }) Some(lib.version) else None
    }
    val version = build.config.setVersion match {
      // calculate some (hopefully unique) default version
      case Some(v) => v
      case _ => {
        val value = build.depInfo.head.baseVersion // We only collect the artifacts from the base level, hence the "head"
        val defaultVersion = (if (value endsWith "-SNAPSHOT") {
          value replace ("-SNAPSHOT", "")
        } else value) +
          (build.config.setVersionSuffix match {
            case None => "-" + ("dbuildx" + build.uuid)
            case Some("") =>
            case Some(suffix) if suffix.startsWith("%commit%") =>
              val len = try { suffix.drop(8).toInt } catch { case e: NumberFormatException => 99999 }
              // note: the suffix must contain at least one letter! numeric-only suffixes will be
              // understood by Maven as some variation over snapshots, leading to unexpected results
              val commit = build.getCommit getOrElse sys.error("This project is unable to provide a commit string, cannot use set-version-suffix: " + build.config.name)
              "-R" + commit.take(len)
            case Some(suffix) => {
              val numbers = ('0' to '9').toSet
              if (suffix.forall(numbers))
                log.warn("*** WARNING: an all-numeric suffix may be interpreted by Maven as a snapshot version; this is probably not what you want (suffix: \"" + suffix + "\"")
              "-" + suffix
            }
          })
        libVersion getOrElse defaultVersion
      }
    }
    // did we set libVersion in order to mark that this project should have a specific version (the one of scala-library),
    // and the current version is not what libVersion is? Emit a warning
    libVersion foreach { lv =>
      if (lv != version) {
        log.warn("*** Warning: project " + build.config.name + " generates a jar of the Scala distribution; its version has been set to")
        log.warn("*** " + version + ", but the version of scala-library is " + lv + ".")
        log.warn("*** The mismatch may cause a scala library not to be found. Please either omit this set-version, or use the same value")
        log.warn("*** in the set-version of both projects.")
      }
    }
    val writeRepo = publishRepo(dir)
    if (!writeRepo.exists()) writeRepo.mkdirs()
    (dependencies, version, writeRepo)
  }
  
}