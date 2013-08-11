package distributed
package project
package build

import model._
import logging.Logger
import akka.actor.Actor
import distributed.project.resolve.ProjectResolver
import actorpatterns.forwardingErrorsToFutures
import java.io.File
import distributed.repo.core._
import sbt.IO
import distributed.project.controller.{Controller, Controlled, Done}

case class RunBuild(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], log: Logger)

/** This actor can run builds locally and return the generated artifacts. */
class BuildRunnerActor(builder: LocalBuildRunner) extends Actor {
  def receive = {
    case Controlled(RunBuild(target, build, outProjects, children, log),from) => 
      Controller.forwardingErrorsToFuturesControlled(sender, from) {
        log info ("--== Building %s ==--" format(build.config.name))
        sender ! Done(builder.checkCacheThenBuild(target, build, outProjects, children, log), from)
        log info ("--== End Building %s ==--" format(build.config.name))
      }
  }   
}