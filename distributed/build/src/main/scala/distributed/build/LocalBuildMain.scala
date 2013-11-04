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

class LocalBuildMain(configuration: xsbti.AppConfiguration) {
  val launcher = configuration.provider.scalaProvider.launcher

  val repos = launcher.ivyRepositories.toList
  val targetDir = ProjectDirs.targetDir
  val resolvers = Seq(
    new support.git.GitProjectResolver,
    new support.svn.SvnProjectResolver,
    new support.ivy.IvyProjectResolver(repos),
    new support.test.TestProjectResolver,
    new support.nil.NilProjectResolver)
  val buildSystems: Seq[BuildSystemCore] =
    Seq(new support.sbt.SbtBuildSystem(repos, targetDir),
      support.scala.ScalaBuildSystem,
      new support.ivy.IvyBuildSystem(repos, targetDir),
      support.mvn.MvnBuildSystem,
      support.test.TestBuildSystem,
      support.nil.NilBuildSystem)

  // Gymnastics for classloader madness

  val system = ClassLoaderMadness.withContextLoader(getClass.getClassLoader)(ActorSystem.create)
  val logMgr = {
    val mgr = system.actorOf(Props(new logging.ChainedLoggerSupervisorActor))
    mgr ! Props(new logging.LogDirManagerActor(new File(targetDir, "logs")))
    mgr ! Props(new logging.SystemOutLoggerActor)
    mgr
  }
  val repository = Repository.default
  val logger = new logging.ActorLogger(logMgr)
  val builder = system.actorOf(Props(new LocalBuilderActor(resolvers, buildSystems, repository, logger)))
  // TODO - Look up target elsewhere...

  def build(conf: DBuildConfiguration, confName: String): BuildOutcome = {
    import akka.pattern.ask
    implicit val timeout: Timeout = Timeouts.dbuildTimeout
    val result = builder ? RunLocalBuild(conf, confName, targetDir)
    Await.result(result.mapTo[BuildOutcome], akka.util.Duration.Inf)
  }
  def dispose(): Unit = system.shutdown()
}
/*
object LocalBuildMain {
  def build(build: DistributedBuildConfig) = {
      val main = new LocalBuildMain
      try main build build
      finally main.dispose
  }
  def main(args: Array[String]): Unit = 
    // TODO - Parse inputs for realz...
    if(args.length == 1) {
      readValue[DistributedBuildConfig](new File(args(0)))
    }
    else System.err.println("Usage: dbuild {build-file}")
}
*/