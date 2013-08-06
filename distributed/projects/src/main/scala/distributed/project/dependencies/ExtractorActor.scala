package distributed
package project
package dependencies

import akka.actor.Actor
import model.ProjectBuildConfig
import actorpatterns.forwardingErrorsToFutures
import _root_.java.io.File
import distributed.project.controller.{ Controller, Controlled, Done }

case class ExtractBuildDependencies(config: ProjectBuildConfig, target: File, log: logging.Logger)

/** An actor that given a BuildConfig can return the completed build artifacts. */
class ExtractorActor(e: Extractor) extends Actor {
  // Extract one build at a time...
  def receive: Receive = {
    case Controlled(ExtractBuildDependencies(build, target, log), from) =>
      Controller.forwardingErrorsToFuturesControlled(sender, from) {
        log info ("--== Extracting dependencies for %s ==--" format (build.name))
        sender ! Done(e.extract(target, build, log), from)
        log info ("--== End Extracting dependencies for %s ==--" format (build.name))
      }
  }
}