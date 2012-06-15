package distributed
package project

import model._
import logging.Logger
import akka.actor.{Actor,ActorRef,Props}


/** This actor can run builds locally and return the generated artifacts. */
class CachedBuildRunnerActor(cache: ActorRef, runner: ActorRef) extends Actor {
  def receive = {
    case r @ RunBuild(build, deps, log) => 
      val interceptor = context.actorOf(Props(new InterceptCacheResultActor(r, sender)))
      log.info("Looking for build: " + build.config)
      cache.!(FindBuildCache(build))(interceptor)
  }

  /** An interceptor for cache responses. */
  class InterceptCacheResultActor(r: RunBuild, listener: ActorRef) extends Actor {
    def receive = {
      case BuildCacheMiss(_) =>
        r.log.info("Running build: " + r.build.config)
        runner.!(r)(listener)  // forward to builder on cache miss.
      case results: BuildArtifacts =>
        r.log.debug("Found cache for build!  Returning immediately.")
        listener ! results // forward results to listener.
    }
  }
}