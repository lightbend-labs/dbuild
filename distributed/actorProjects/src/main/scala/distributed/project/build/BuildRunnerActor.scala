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
import sbt.Path._
import distributed.utils.Time._

case class RunBuild(build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], log: Logger)

/** This actor can run builds locally and return the generated artifacts. */
class BuildRunnerActor(builder: LocalBuildRunner, target: File, exp: CleanupExpirations) extends Actor {
  override def preStart() = {
    // Cleanup works in two stages; see ExtractorActor for details.
    // Note that cleanup is performed independently for the extraction and build directories
    target.*(sbt.DirectoryFilter).get.filter(upForDeletion(_, exp)).foreach(prepareForDeletion)
  }
    
  def receive = {
    case Controlled(RunBuild(build, outProjects, children, log),from) => 
      Controller.forwardingErrorsToFuturesControlled(sender, from) {
        log info ("--== Building %s ==--" format(build.config.name))
        sender ! Done(builder.checkCacheThenBuild(target, build, outProjects, children, log), from)
        log info ("--== End Building %s ==--" format(build.config.name))
      }
  }   
}