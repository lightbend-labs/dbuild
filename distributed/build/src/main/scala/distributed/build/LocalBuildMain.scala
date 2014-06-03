package distributed
package build

import java.io.File
import akka.actor.{ ActorSystem, Props }
import akka.dispatch.Await
import akka.util.Timeout
import akka.util.duration._
import project.model._
import distributed.project.model.Utils.{ readValue, writeValue }
import distributed.repo.core._
import distributed.project.model.ClassLoaderMadness
import distributed.project.dependencies.Extractor
import distributed.support.BuildSystemCore
import akka.pattern.ask
import akka.util.duration._
import distributed.repo.core.GlobalDirs.checkForObsoleteDirs

class LocalBuildMain(repos: List[xsbti.Repository], options: BuildRunOptions) {

  val targetDir = GlobalDirs.targetDir
  val resolvers = Seq(
    new support.git.GitProjectResolver,
    new support.svn.SvnProjectResolver,
    new support.ivy.IvyProjectResolver(repos),
    new support.test.TestProjectResolver,
    new support.nil.NilProjectResolver)
  val buildSystems: Seq[BuildSystemCore] =
    Seq(new support.sbt.SbtBuildSystem(repos, targetDir, options.debug),
      support.scala.ScalaBuildSystem,
      new support.ivy.IvyBuildSystem(repos, targetDir),
      support.mvn.MvnBuildSystem,
      support.assemble.AssembleBuildSystem,
      support.test.TestBuildSystem,
      support.nil.NilBuildSystem)

  // Gymnastics for classloader madness

  val system = ClassLoaderMadness.withContextLoader(getClass.getClassLoader)(ActorSystem.create)
  val logMgr = {
    val mgr = system.actorOf(Props(new logging.ChainedLoggerSupervisorActor))
    mgr ! Props(new logging.LogDirManagerActor(new File(targetDir, "logs")))
    mgr ! Props(new logging.SystemOutLoggerActor(options.debug))
    mgr
  }
  val repository = Repository.default
  val logger = new logging.ActorLogger(logMgr)
  checkForObsoleteDirs(logger.warn _)

  val builder = system.actorOf(Props(new LocalBuilderActor(resolvers, buildSystems, repository, targetDir, logger, options)))
  // TODO - Look up target elsewhere...

  def build(conf: DBuildConfiguration, confName: String, buildTarget: Option[String]): BuildOutcome = {
    implicit val timeout: Timeout = Timeouts.dbuildTimeout
    val result = builder ? RunLocalBuild(conf, confName, buildTarget)
    Await.result(result.mapTo[BuildOutcome], akka.util.Duration.Inf)
  }
  def dispose(): Unit = {
    implicit val timeout: Timeout = 5.minutes
    Await.result((logMgr ? "exit").mapTo[String], akka.util.Duration.Inf)
    system.shutdown() // pro forma, as all loggers should already be stopped at this point
    system.awaitTermination(1.minute)
  }
}