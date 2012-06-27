package distributed
package build

import project.BuildSystem
import project.resolve.ProjectResolver
import project.dependencies.ExtractorActor
import project.build.BuildRunnerActor
import project.model._
import logging.Logger
import akka.actor.{Actor,Props}

/** This is an actor which executes builds locally given a
 * set of resolvers and build systems.
 * 
 * This forward builds configurations onto the build system.
 */
class LocalBuilderActor(
    resolvers: Seq[ProjectResolver], 
    buildSystems: Seq[BuildSystem], 
    log: Logger) extends Actor {
  
  
  val resolver = new project.resolve.AggregateProjectResolver(resolvers)
  val depExtractor = new project.dependencies.MultiBuildDependencyExtractor(buildSystems)
  val extractor = new project.dependencies.Extractor(resolver, depExtractor)
  val buildRunner = new project.build.AggregateBuildRunner(buildSystems)
  
  val extractorActor = context.actorOf(Props(new ExtractorActor(extractor)), "Project-Dependency-Extractor")
  val baseBuildActor = context.actorOf(Props(new BuildRunnerActor(buildRunner, resolver)), "Project-Builder")
  val fullBuilderActor = context.actorOf(Props(new SimpleBuildActor(extractorActor, baseBuildActor)), "simple-distributed-builder")

  def receive = {
    case config: DistributedBuildConfig => 
      fullBuilderActor forward RunDistributedBuild(config, log)
  }
}