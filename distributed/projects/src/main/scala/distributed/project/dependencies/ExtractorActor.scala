package distributed
package project
package dependencies

import akka.actor.Actor
import model.BuildConfig

case class ExtractBuildDependencies(config: BuildConfig, log: logging.Logger)

/** An actor that given a BuildConfig can return the completed build artifacts. */
class ExtractorActor(e: Extractor) extends Actor {
  // Extract one build at a time...
  def receive: Receive = {
    case ExtractBuildDependencies(build, log) => 
      log.info("ExtractorActor - Extracting dependencies for: " + build.uri)
      sender ! e.extract(build, log)
  }
}