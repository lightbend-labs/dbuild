package distributed
package project
package dependencies

import akka.actor.Actor
import model.{ProjectBuildConfig, ExtractionConfig}
import actorpatterns.forwardingErrorsToFutures
import _root_.java.io.File
import sbt.Path._
import distributed.project.controller.{ Controller, Controlled, Done }
import distributed.repo.core.ProjectDirs.extractionDir

case class ExtractBuildDependencies(config: ExtractionConfig, uuidDir: String, log: logging.Logger)

/** An actor that given a BuildConfig can return the completed build artifacts.
 *  "target" is the target dir, e.g. "../target-0.7.1". Inside it, we nest "extraction",
 *  plus an uuid that represents the entire dbuild configuration. In that, extract()
 *  will nest "project/uuid", with uuid referring to the single project being extracted.
 */
class ExtractorActor(e: Extractor, target: File) extends Actor {
  override def preStart() = {
    // TODO: implement initial stage of cleanup, then spawn an auxiliary actor that
    // will clean in the background (possibly being interrupted at any time, should
    // dbuild exit half-way through).
    // TODO: distinguish between successful and unsuccessful extractions, by touching
    // a file ".dbuild-success" at the end of a successful extraction.
    // TODO: add some sort of locking, in case multiple extractor actors start in the
    // same dir, from two instances of dbuild (this has to be done in the general
    // context of adding locking to everything)
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