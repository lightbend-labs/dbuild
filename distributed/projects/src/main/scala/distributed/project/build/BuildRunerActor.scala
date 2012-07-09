package distributed
package project
package build

import model._
import logging.Logger
import akka.actor.Actor
import distributed.project.resolve.ProjectResolver
import actorpaterns.forwardingErrorsToFutures

case class RunBuild(build: Build, dependencies: BuildArtifacts, log: Logger)

/** This actor can run builds locally and return the generated artifacts. */
class BuildRunnerActor(builder: BuildRunner, resolver: ProjectResolver) extends Actor {
  def receive = {
    case RunBuild(build, deps, log) => 
      forwardingErrorsToFutures(sender) {
        log info ("--== Building %s ==--" format(build.config.name))
        sender ! runLocalBuild(build, deps, log)
      }
      
  }
  /** Runs the build locally in its hashed directory.
   * TODO - Conflicts? Locking? good code?
   */
  def runLocalBuild(build: Build, dependencies: BuildArtifacts, log: Logger): BuildArtifacts =
    local.ProjectDirs.useDirFor(build.config) { dir =>
      log.info("Resolving: " + build.config.uri + " in directory: " + dir)
      resolver.resolve(build.config, dir, log)
      log.info("Running local build: " + build.config + " in directory: " + dir)
      builder.runBuild(build, dir, dependencies, log)
    }
}