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