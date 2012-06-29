package distributed
package build

import java.io.File
import akka.actor.{ActorSystem,Props}
import akka.dispatch.Await
import akka.util.Timeout
import akka.util.duration._
import distributed.project.model.{DistributedBuildParser,BuildArtifacts,DistributedBuildConfig}

class LocalBuildMain(workingDir: File = local.ProjectDirs.builddir) {
  // TODO - Pull these via plugins or something...
  val targetDir = new File(workingDir, "target")
  // Maybe even read global config for each module...
  val resolvers = Seq(new support.git.GitProjectResolver)
  val buildSystems: Seq[project.BuildSystem] = 
    Seq(new support.sbt.SbtBuildSystem(targetDir), support.scala.ScalaBuildSystem)
  
  // Gymnastics for classloader madness

  val system = ClassLoaderMadness.withContextLoader(getClass.getClassLoader)(ActorSystem.create)
  val logMgr = {
    val mgr = system.actorOf(Props(new logging.ChainedLoggerSupervisorActor))
    mgr ! Props(new logging.LogDirManagerActor(new File(targetDir, "logs")))
    mgr ! Props(new logging.SystemOutLoggerActor)
    mgr
  }
  val logger = new logging.ActorLogger(logMgr)
  val builder = system.actorOf(Props(new LocalBuilderActor(resolvers, buildSystems, logger)))
  
  def build(build: DistributedBuildConfig): BuildArtifacts = {
    import akka.pattern.ask
    implicit val timeout: Timeout = (4).hours
    val result = builder ? build
    Await.result(result.mapTo[BuildArtifacts], (4).hours)
  }
  def dispose(): Unit = system.shutdown()
}
object LocalBuildMain {
  def build(build: DistributedBuildConfig) = {
      val main = new LocalBuildMain
      try main build build
      finally main.dispose
  }
  def main(args: Array[String]): Unit = 
    // TODO - Parse inputs for realz...
    if(args.length == 1) build(DistributedBuildParser parseBuildFile new File(args(0)))
    else System.err.println("Usage: dbuild {build-file}")
}