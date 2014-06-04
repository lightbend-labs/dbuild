package distributed
package project
package build

import model._
import logging.Logger
import akka.actor.{ ActorRef, Actor, Props, PoisonPill, Terminated }
import distributed.project.resolve.ProjectResolver
import actorpatterns.forwardingErrorsToFutures
import java.io.File
import distributed.repo.core._
import sbt.IO
import distributed.project.controller.{ Controller, Controlled, Done }
import distributed.project.cleanup.Recycling._
import sbt.Path._
import distributed.repo.core.ProjectDirs.buildDir

case class RunBuild(build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], buildData: BuildData)

class CleaningBuildActor extends Actor {
  def receive = {
    case target: File =>
      IO.delete(buildDir(target).*(sbt.DirectoryFilter).get.filter(markedForDeletion))
      self ! PoisonPill
  }
}
/** This actor can run builds locally and return the generated artifacts. */
class BuildRunnerActor(builder: LocalBuildRunner, target: File, exp: CleanupExpirations) extends Actor {
  override def preStart() = {
    // Cleanup works in two stages; see ExtractorActor for details.
    // Note that cleanup is performed independently for the extraction and build directories
    buildDir(target).*(sbt.DirectoryFilter).get.filter(upForDeletion(_, exp)).foreach(prepareForDeletion)
    // spawn the cleaning actor
    context.actorOf(Props(new CleaningBuildActor)) ! target
  }

  def receive = {
    case Controlled(RunBuild(build, outProjects, children, buildData@BuildData(log, _)), from) =>
      Controller.forwardingErrorsToFuturesControlled(sender, from) {
        log info ("--== Building %s ==--" format (build.config.name))
        sender ! Done(builder.checkCacheThenBuild(target, build, outProjects, children, buildData), from)
        log info ("--== End Building %s ==--" format (build.config.name))
      }
  }
}