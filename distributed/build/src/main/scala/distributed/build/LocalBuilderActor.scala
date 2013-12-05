package distributed
package build

import project.BuildSystem
import distributed.support.BuildSystemCore
import project.resolve.ProjectResolver
import project.dependencies.ExtractorActor
import project.build.BuildRunnerActor
import project.model._
import logging.Logger
import akka.actor.{Actor,Props,ActorRef,ActorContext}
import java.io.File
import distributed.repo.core.Repository
import distributed.project.controller.Controller
import distributed.project.dependencies.Extractor

case class RunLocalBuild(config: DBuildConfiguration, configName: String, buildTarget: Option[String])
/** This is an actor which executes builds locally given a
 * set of resolvers and build systems.
 * 
 * This forward builds configurations onto the build system.
 */
class LocalBuilderActor(
    resolvers: Seq[ProjectResolver],
    buildSystems: Seq[BuildSystemCore],
    repository: Repository,
    targetDir: File,
    cleanup: CleanupOptions,
    log: Logger) extends Actor {

  val concurrencyLevel = 1
  
  val resolver = new project.resolve.AggregateProjectResolver(resolvers)
  val depExtractor = new project.dependencies.MultiBuildDependencyExtractor(buildSystems)
  val extractor = new project.dependencies.Extractor(resolver, depExtractor, repository)
  val buildRunner = new project.build.AggregateBuildRunner(buildSystems)
  val localBuildRunner = new project.build.LocalBuildRunner(buildRunner, extractor, repository)
  
  val extractorActor = Controller(context,
      Props(new ExtractorActor(extractor, targetDir, cleanup.extraction)),
      "Project-Dependency-Extractor", concurrencyLevel)
  val baseBuildActor = Controller(context,
      Props(new BuildRunnerActor(localBuildRunner, targetDir, cleanup.build)),
      "Project-Builder", concurrencyLevel)
  val fullBuilderActor = context.actorOf(Props(new SimpleBuildActor(extractorActor, baseBuildActor, repository, buildSystems)), "simple-distributed-builder")

  def receive = {
    case RunLocalBuild(config, configName, buildTarget) =>
      fullBuilderActor forward RunDistributedBuild(config, configName, buildTarget, log)
  }
}
