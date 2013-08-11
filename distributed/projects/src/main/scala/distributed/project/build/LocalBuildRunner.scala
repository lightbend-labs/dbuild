package distributed
package project
package build

import model._
import logging.Logger
import akka.actor.Actor
import distributed.project.resolve.ProjectResolver
import actorpatterns.forwardingErrorsToFutures
import Logger.prepareLogMsg
import java.io.File
import distributed.repo.core._
import sbt.Path._

/** This class encodes the logic to resolve a project and run its build given
 * a local repository, a resolver and a build runner.
 */
class LocalBuildRunner(builder: BuildRunner, 
    resolver: ProjectResolver, 
    repository: Repository) {

  def checkCacheThenBuild(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], log: Logger): BuildOutcome = {
    try {
      try {
        BuildUnchanged(build.config.name, children, BuildArtifactsOut(LocalRepoHelper.getPublishedDeps(build.uuid, repository)))
      } catch {
        case t: RepositoryException =>
          log.debug("Failed to resolve: " + build.uuid + " from " + build.config.name)
          //log.trace(t)
          BuildSuccess(build.config.name, children, runLocalBuild(target, build, outProjects, log))
      }
    } catch {
      case t =>
        BuildFailed(build.config.name, children, prepareLogMsg(log,t))
    }
  }
  
  def runLocalBuild(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project], log: Logger): BuildArtifactsOut =
    distributed.repo.core.ProjectDirs.useProjectUniqueBuildDir(build.config.name + "-" + build.uuid, target) { dir =>
      log.info("Resolving: " + build.config.uri + " in directory: " + dir)
      resolver.resolve(build.config, dir, log)
      log.info("Resolving artifacts")
      val dbuildDir=builder.projectDbuildDir(dir,build.config)
      val readRepo = dbuildDir / "local-repo"
      val writeRepo = dbuildDir / "local-publish-repo"
      if(!writeRepo.exists()) writeRepo.mkdirs()
      val uuids = build.transitiveDependencyUUIDs.toSeq
      val artifactLocations = LocalRepoHelper.getArtifactsFromUUIDs(log.info, repository, readRepo, uuids)
      // TODO - Load this while resolving!
      val dependencies: BuildArtifactsIn = BuildArtifactsIn(artifactLocations, readRepo)
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
      val scalaLib = artifactLocations find { a =>
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
          val value = build.baseVersion
          val defaultVersion = (if (value endsWith "-SNAPSHOT") {
            value replace ("-SNAPSHOT", "")
          } else value) + "-dbuildx" + build.uuid
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
      log.info("Running local build: " + build.config + " in directory: " + dir)
      val results = builder.runBuild(build, dir, BuildInput(dependencies, build.uuid, version, build.subproj, writeRepo, build.config.name), log)
      // TODO - We pull out just the artifacts published and push them again
      LocalRepoHelper.publishProjectArtifactInfo(build, results.results, writeRepo, repository, log)
      results
    }
  
}