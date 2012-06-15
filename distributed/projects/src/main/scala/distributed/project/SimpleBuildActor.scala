package distributed
package project

import model._
import dependencies.ExtractBuildDependencies
import logging.Logger
import akka.actor.{Actor,ActorRef,Props}
import akka.pattern.ask
import akka.dispatch.{Future,Futures}
import akka.util.duration._
import akka.util.Timeout
import graph.Graphs

case class RunDistributedBuild(build: DistributedBuildConfig, logger: Logger)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef) extends Actor {
  def receive = {
    case RunDistributedBuild(build, log) =>
      val listener = sender
      for {
        fullBuild <- analyze(build, log)
        results <- runBuild(fullBuild, log)
      } listener ! results
  }
  
  
  
  implicit val buildTimeout: Timeout = 4 hours 

  // Chain together some Asynch to run this build.
  def runBuild(build: DistributedBuild, log: Logger): Future[BuildArtifacts] = {
    implicit val ctx = context.system
    
    def runBuild(builds: List[Build], fArts: Future[BuildArtifacts]): Future[BuildArtifacts] = 
      builds match {
        case b :: rest =>
          val nextArts = for {
            arts <- fArts
            newArts <- buildProject(b, arts, log.newNestedLogger(b.config.name))
          } yield BuildArtifacts(arts.artifacts ++ newArts.artifacts)
          runBuild(rest, nextArts)
        case _ => fArts
      }
    runBuild(build.builds.toList, Future(BuildArtifacts(Seq.empty)))
  }  
  
  // Asynchronously extract information from builds.
  def analyze(config: DistributedBuildConfig, log: Logger): Future[DistributedBuild] = {
    implicit val ctx = context.system
    val builds: Future[Seq[Build]] = 
      Future.traverse(config.projects)(extract(log))
    // Now determine build ordering.  
    val ordered = builds map { seq =>
      val graph = new BuildGraph(seq)
      (Graphs safeTopological graph map (_.value)).reverse
    }
    // Now we need them in build ordering...
    ordered map DistributedBuild.apply 
  } 
  
  // Our Asynchronous API.
  def extract(logger: Logger)(config: BuildConfig): Future[Build] =
    (extractor ? ExtractBuildDependencies(config, logger.newNestedLogger(config.name))).mapTo[Build]
  def buildProject(build: Build, deps: BuildArtifacts, logger: Logger): Future[BuildArtifacts] =
    (builder ? RunBuild(build, deps, logger)).mapTo[BuildArtifacts]
}