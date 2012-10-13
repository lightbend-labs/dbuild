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
import sbt.IO

case class RunBuild(target: File, build: RepeatableProjectBuild, log: Logger)

/** This actor can run builds locally and return the generated artifacts. */
class BuildRunnerActor(builder: LocalBuildRunner) extends Actor {
  def receive = {
    case RunBuild(target, build, log) => 
      forwardingErrorsToFutures(sender) {
        log info ("--== Building %s ==--" format(build.config.name))
        sender ! builder.checkCacheThenBuild(target, build, log)
      }
  }
  
   
}

/** This class encodes the logic to resolve a project and run its build given
 * a local repository, a resolver and a build runner.
 */
class LocalBuildRunner(builder: BuildRunner, 
    resolver: ProjectResolver, 
    repository: Repository) {
  
  def checkCacheThenBuild(target: File, build: RepeatableProjectBuild, log: Logger): BuildArtifacts = 
    try IO.withTemporaryDirectory { dir =>
      // TODO - Don't waste time copying files!!!!!!
      val deps = LocalRepoHelper.materializeProjectRepository(build.uuid, repository, dir)
      BuildArtifacts(deps, dir)
    } catch {
      case e: Exception => runLocalBuild(target, build, log)
    }
  
  def runLocalBuild(target: File, build: RepeatableProjectBuild, log: Logger): BuildArtifacts =
    local.ProjectDirs.useProjectUniqueBuildDir(build.uuid, target) { dir =>
      log.info("Resolving: " + build.config.uri + " in directory: " + dir)
      resolver.resolve(build.config, dir, log)
      log.info("Resolving artifacts")
      val readRepo = new File(dir, ".dsbt/local-repo")
      val writeRepo = new File(dir, ".dsbt/local-publish-repo")
      if(!writeRepo.exists()) writeRepo.mkdirs()
      val artifactLocations = for {
        dep <- build.dependencies
        art <- LocalRepoHelper.materializeProjectRepository(dep.uuid, repository, readRepo)
      } yield art
      // TODO - Load this while resolving!
      val dependencies: BuildArtifacts = BuildArtifacts(artifactLocations, readRepo)
      log.info("Running local build: " + build.config + " in directory: " + dir)
      val results = builder.runBuild(build, dir, BuildInput(dependencies, writeRepo), log)
      // TODO - We pull out just the artifacts published and push them again
      LocalRepoHelper.publishProjectArtiactInfo(build, results.artifacts, writeRepo, repository)
      results
    }
  
}