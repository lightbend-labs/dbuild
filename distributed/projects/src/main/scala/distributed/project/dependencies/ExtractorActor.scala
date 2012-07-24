package distributed
package project
package dependencies

import akka.actor.Actor
import model.BuildConfig
import actorpaterns.forwardingErrorsToFutures
import _root_.java.io.File

case class ExtractBuildDependencies(config: BuildConfig, target: File, log: logging.Logger)

/** An actor that given a BuildConfig can return the completed build artifacts. */
class ExtractorActor(e: Extractor) extends Actor {
  // Extract one build at a time...
  def receive: Receive = {
    case ExtractBuildDependencies(build, target, log) => forwardingErrorsToFutures(sender) {
      log.info("ExtractorActor - Extracting dependencies for: " + build.uri)
      sender ! e.extract(target, build, log)
    }
  }
}