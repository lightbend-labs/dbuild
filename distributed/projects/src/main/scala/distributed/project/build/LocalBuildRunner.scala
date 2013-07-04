package distributed
package project
package build

import model._
import logging.Logger
import akka.actor.Actor
import distributed.project.resolve.ProjectResolver
import actorpaterns.forwardingErrorsToFutures
import java.io.File
import distributed.repo.core._
import sbt.Path._

/** This class encodes the logic to resolve a project and run its build given
 * a local repository, a resolver and a build runner.
 */
class LocalBuildRunner(builder: BuildRunner, 
    resolver: ProjectResolver, 
    repository: Repository) {
  
  def checkCacheThenBuild(target: File, build: RepeatableProjectBuild, log: Logger): BuildArtifactsOut = 
    try BuildArtifactsOut(LocalRepoHelper.getPublishedDeps(build.uuid, repository))
    catch {
      case t: RepositoryException => 
        log.info("Failed to resolve: " + build.uuid + " from " + build.config.name)
        log.trace(t)
        runLocalBuild(target, build, log)
    } 
  
  def runLocalBuild(target: File, build: RepeatableProjectBuild, log: Logger): BuildArtifactsOut =
    distributed.project.ProjectDirs.useProjectUniqueBuildDir(build.config.name + "-" + build.uuid, target) { dir =>
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
      val version = build.config.setVersion match {
        case Some(v) => v
        case _ => {
          val value = build.baseVersion
          (if (value endsWith "-SNAPSHOT") {
            value replace ("-SNAPSHOT", "")
          } else value) + "-" + build.uuid + "-dbuild"
        }
      }
      log.info("Running local build: " + build.config + " in directory: " + dir)
      val results = builder.runBuild(build, dir, BuildInput(dependencies, build.uuid, version, build.subproj, writeRepo), log)
      // TODO - We pull out just the artifacts published and push them again
      LocalRepoHelper.publishProjectArtifactInfo(build, results.artifacts, writeRepo, repository)
      results
    }
  
}