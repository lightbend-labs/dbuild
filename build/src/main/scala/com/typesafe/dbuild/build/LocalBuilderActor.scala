package com.typesafe.dbuild.build

import com.typesafe.dbuild.project.BuildSystem
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.project.resolve.ProjectResolver
import com.typesafe.dbuild.project.dependencies.TimedExtractorActor
import com.typesafe.dbuild.project.build.TimedBuildRunnerActor
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import akka.actor.{Actor,Props,ActorRef,ActorContext}
import java.io.File
import com.typesafe.dbuild.repo.core.Repository
import com.typesafe.dbuild.project.dependencies.Extractor

case class RunLocalBuild(config: DBuildConfiguration, configName: String, buildTarget: Option[String])
/**
 * This is an actor which executes builds locally given a
 * set of resolvers and build systems.
 *
 * This forward builds configurations onto the build system.
 */
class LocalBuilderActor(
    resolvers: Seq[ProjectResolver],
    buildSystems: Seq[BuildSystemCore],
    repository: Repository,
    targetDir: File,
    log: Logger, options: BuildRunOptions) extends Actor {

  val concurrencyLevel = 1

  val resolver = new com.typesafe.dbuild.project.resolve.AggregateProjectResolver(resolvers)
  val depExtractor = new com.typesafe.dbuild.project.dependencies.MultiBuildDependencyExtractor(buildSystems)
  val extractor = new com.typesafe.dbuild.project.dependencies.Extractor(resolver, depExtractor, repository)
  val buildRunner = new com.typesafe.dbuild.project.build.AggregateBuildRunner(buildSystems)
  val localBuildRunner = new com.typesafe.dbuild.project.build.LocalBuildRunner(buildRunner, extractor, repository)

  val extractorActor = context.actorOf(
      Props(classOf[TimedExtractorActor], extractor, targetDir, options.cleanup.extraction, options.timeouts.extractionTimeout),
      "Project-Dependency-Extractor")

  val baseBuildActor = context.actorOf(
      Props(classOf[TimedBuildRunnerActor], localBuildRunner, targetDir, options.cleanup.build, options.timeouts.buildTimeout),
      "Project-Builder")

  val fullBuilderActor = context.actorOf(Props(new SimpleBuildActor(extractorActor, baseBuildActor, repository, buildSystems)), "simple-builder")

  def receive = {
    case RunLocalBuild(config, configName, buildTarget) =>
      fullBuilderActor forward RunDBuild(config, configName, buildTarget, log, options)
  }
}
