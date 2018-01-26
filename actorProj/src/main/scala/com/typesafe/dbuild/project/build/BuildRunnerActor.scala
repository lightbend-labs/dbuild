package com.typesafe.dbuild.project.build

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import akka.actor.{ ActorRef, Actor, Props, PoisonPill, Terminated }
import com.typesafe.dbuild.project.resolve.ProjectResolver
import com.typesafe.dbuild.project.build.ActorPatterns.forwardingErrorsToFutures
import java.io.File
import com.typesafe.dbuild.repo.core._
import sbt.IO
import com.typesafe.dbuild.project.cleanup.Recycling._
import sbt.Path._
import com.typesafe.dbuild.repo.core.GlobalDirs.buildDir
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }

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
    case RunBuild(build, outProjects, children, buildData@BuildData(log, _)) =>
      forwardingErrorsToFutures(sender) {
        log info ("--== Building %s ==--" format (build.config.name))
        sender ! builder.checkCacheThenBuild(target, build, outProjects, children, buildData)
        log info ("--== End Building %s ==--" format (build.config.name))
      }
  }
}
