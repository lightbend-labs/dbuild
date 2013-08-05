package distributed
package project
package dependencies

import akka.actor.Actor
import model.ProjectBuildConfig
import actorpaterns.forwardingErrorsToFutures
import _root_.java.io.File

case class ExtractBuildDependencies(config: ProjectBuildConfig, target: File, log: logging.Logger)

/** An actor that given a BuildConfig can return the completed build artifacts. */
class ExtractorActor(e: Extractor) extends Actor {
  // Extract one build at a time...
  def receive: Receive = {
    case ExtractBuildDependencies(build, target, log) => forwardingErrorsToFutures(sender) {
      log info ("--== Extracting dependencies for %s ==--" format(build.name))
      sender ! e.extract(target, build, log)
      log info ("--== End Extracting dependencies for %s ==--" format(build.name))
    }
  }
}