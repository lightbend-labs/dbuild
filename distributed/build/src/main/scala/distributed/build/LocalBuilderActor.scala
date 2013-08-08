package distributed
package build

import project.BuildSystem
import project.resolve.ProjectResolver
import project.dependencies.ExtractorActor
import project.build.BuildRunnerActor
import project.model._
import logging.Logger
import akka.actor.{Actor,Props,ActorRef,ActorContext}
import java.io.File
import distributed.repo.core.Repository
import distributed.project.controller.Controller

case class RunLocalBuild(config: DBuildConfiguration, targetDir: File)
/** This is an actor which executes builds locally given a
 * set of resolvers and build systems.
 * 
 * This forward builds configurations onto the build system.
 */
class LocalBuilderActor(
    resolvers: Seq[ProjectResolver], 
    buildSystems: Seq[BuildSystem],
    repository: Repository,
    log: Logger) extends Actor {

  val concurrencyLevel = 1
  
  val resolver = new project.resolve.AggregateProjectResolver(resolvers)
  val depExtractor = new project.dependencies.MultiBuildDependencyExtractor(buildSystems)
  val extractor = new project.dependencies.Extractor(resolver, depExtractor, repository)
  val buildRunner = new project.build.AggregateBuildRunner(buildSystems)
  val locaBuildRuner = new project.build.LocalBuildRunner(buildRunner, resolver, repository)
  
  val extractorActor = Controller(context, Props(new ExtractorActor(extractor)), "Project-Dependency-Extractor", concurrencyLevel)
  val baseBuildActor = Controller(context, Props(new BuildRunnerActor(locaBuildRuner)), "Project-Builder", concurrencyLevel)
  val fullBuilderActor = context.actorOf(Props(new SimpleBuildActor(extractorActor, baseBuildActor, repository)), "simple-distributed-builder")

  def receive = {
    case RunLocalBuild(config, target) => 
      fullBuilderActor forward RunDistributedBuild(config, target, log)
  }
}
