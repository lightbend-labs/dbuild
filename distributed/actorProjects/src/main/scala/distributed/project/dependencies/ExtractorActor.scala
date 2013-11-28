package distributed
package project
package dependencies

import akka.actor.{ ActorRef, Actor, Props, PoisonPill, Terminated }
import model.{ ProjectBuildConfig, ExtractionConfig }
import actorpatterns.forwardingErrorsToFutures
import _root_.java.io.File
import sbt.Path._
import distributed.project.controller.{ Controller, Controlled, Done }
import distributed.repo.core.ProjectDirs.{ extractionDir, projectExtractionDir }
import distributed.project.model.CleanupExpirations
import distributed.project.cleanup.Recycling._
import sbt.{ IO, DirectoryFilter }

case class ExtractBuildDependencies(config: ExtractionConfig, uuidDir: String, log: logging.Logger)

class CleaningExtractionActor extends Actor {
  def receive = {
    case target: File =>
      extractionDir(target).*(DirectoryFilter).get.foreach { d1 =>
        if (markedForDeletion(d1))
          IO.delete(d1)
        else
          IO.delete(projectExtractionDir(d1).*(DirectoryFilter).get.filter(markedForDeletion))
      }
      self ! PoisonPill
  }
}
/**
 * An actor that given a BuildConfig can return the completed build artifacts.
 *  "target" is the target dir, e.g. "../target-0.7.1". Inside it, we nest "extraction",
 *  plus an uuid that represents the entire dbuild configuration. In that, extract()
 *  will nest "project/uuid", with uuid referring to the single project being extracted.
 */
class ExtractorActor(e: Extractor, target: File, exp: CleanupExpirations) extends Actor {
  override def preStart() = {
    // Cleanup works in two stages.
    // Before any extraction is performed, in a quick pass the extraction directory
    // is scanned, and the directories that are eligible for cleanup are renamed to
    // "delete-...". This is done synchronously in the preStart() initialization stage
    // of the actor.
    // Once that is done, a further actor (which may be killed at any time) is spawned
    // to actually do the cleanup, and this second stage is performed asynchronously.
    // Thanks to this mechanism, even if dbuild stops half-way through the deletion,
    // the next iteration will find the directories that have been previously renamed
    // as eligibile for deletion anyway.
    // Further, renaming the directories gets them out of the way, in case the extractor
    // needs to use the same directory name again, for a new extraction.

    // There are two levels in the hierarchy, so we mark for deletion first the nested
    // ones and, if all the content can be deleted, the outer one as well.
    extractionDir(target).*(DirectoryFilter).get.foreach { d1 =>
      if (!markedForDeletion(d1)) { // skip if already marked
        val candidates = projectExtractionDir(d1).*(DirectoryFilter).get
        val (delete, doNotDelete) = candidates.partition(upForDeletion(_, exp))
        if (doNotDelete.isEmpty) // everything can be deleted inside this dir, or there is
          prepareForDeletion(d1) // nothing left, so remove the dir
        else
          delete.foreach(prepareForDeletion) // mark for deletion only the relevant subdirs
      }
    }
    // spawn the cleaning actor
    context.actorOf(Props(new CleaningExtractionActor)) ! target
    // TODO: distinguish between successful and unsuccessful extractions, by touching
    // a file ".dbuild-success" at the end of a successful extraction.
    // TODO: add some sort of locking, in case multiple extractor actors start in the
    // same dir, from two instances of dbuild (this has to be done in the general
    // context of adding locking to everything). Note that although renaming is atomic,
    // we also have to get rid of the timestamp and success files; further, we cannot
    // determine accurately the age of directories if unrelated extractors are running
    // at the same time, so some form of auxiliary locking is needed anyway.
  }
  def receive: Receive = {
    case Controlled(ExtractBuildDependencies(build, uuidDir, log), from) =>
      Controller.forwardingErrorsToFuturesControlled(sender, from) {
        log info ("--== Extracting dependencies for %s ==--" format (build.buildConfig.name))
        sender ! Done(e.extract(extractionDir(target) / uuidDir, build, log), from)
        log info ("--== End Extracting dependencies for %s ==--" format (build.buildConfig.name))
      }
  }
}