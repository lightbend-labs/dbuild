package distributed
package build

import project.model._
import project.build._
import repo.core.{Repository,LocalRepoHelper}
import project.dependencies.ExtractBuildDependencies
import logging.Logger
import akka.actor.{Actor,ActorRef,Props}
import akka.pattern.{ask,pipe}
import akka.dispatch.{Future,Futures}
import akka.util.duration._
import akka.util.Timeout
import actorpaterns.forwardingErrorsToFutures
import sbt.Path._
import java.io.File

case class RunDistributedBuild(build: DistributedBuildConfig, target: File, logger: Logger)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef, repository: Repository) extends Actor {
  def receive = {
    case RunDistributedBuild(build, target, log) => forwardingErrorsToFutures(sender) {
      val listener = sender
      val logger = log.newNestedLogger(hashing sha1 build)
      // "build" contains the project configs as written in the configuration file.
      // Their 'extra' field could be None, or contain information that must be completed
      // according to the build system in use for that project.
      // Only each build system knows its own defaults (which may change over time),
      // therefore we have to ask to the build system itself to expand the 'extra' field
      // as appropriate.
      val result = for {
        fullBuild <- analyze(build, target, log.newNestedLogger(hashing sha1 build))
        fullLogger = log.newNestedLogger(fullBuild.uuid)
        _ = publishFullBuild(fullBuild, fullLogger)
        arts <- runBuild(target, fullBuild, fullLogger)
      } yield arts
      result pipeTo listener
    }
  }
  
  /** Publishing the full build to the repository and logs the output for
   * re-use.
   */
  def publishFullBuild(build: RepeatableDistributedBuild, log: Logger): Unit = {
    log.info("---==  RepeatableBuild ==---")
    log.info(" uuid = " + build.uuid)
    log.info("---==   Repeatable Build Config   ===---")
    log.info(build.repeatableBuildString)
    log.info("---== End Repeatable Build Config ===---")
    LocalRepoHelper.publishBuildMeta(build, repository)
  }
  
  def logPoms(build: RepeatableDistributedBuild, arts: BuildArtifacts, log: Logger): Unit = 
    try {
      log info "Printing Poms!"
      val poms = repo.PomHelper.makePomStrings(build, arts)
      log info (poms mkString "----------")
    } catch {
      case e: Throwable => 
        log trace e
        throw e
    }
  
  implicit val buildTimeout: Timeout = 4 hours 

  // Chain together some Asynch to run this build.
  def runBuild(target: File, build: RepeatableDistributedBuild, log: Logger): Future[BuildArtifacts] = {
    implicit val ctx = context.system
    val tdir = local.ProjectDirs.targetDir
    def runBuild(builds: List[RepeatableProjectBuild], fArts: Future[BuildArtifacts]): Future[BuildArtifacts] = 
      builds match {
        case b :: rest =>
          val nextArts = for {
            arts <- fArts
            newArts <- buildProject(tdir, b, log.newNestedLogger(b.config.name))
          } yield BuildArtifacts(arts.artifacts ++ newArts.artifacts, arts.localRepo)
          runBuild(rest, nextArts)
        case _ => fArts
      }
    
    // TODO - REpository management here!!!!
    local.ProjectDirs.userRepoDirFor(build) { localRepo =>      
      runBuild(build.repeatableBuilds.toList, Future(BuildArtifacts(Seq.empty, localRepo)))
    }
  }  
  
  // Asynchronously extract information from builds.
  def analyze(config: DistributedBuildConfig, target: File, log: Logger): Future[RepeatableDistributedBuild] = {
    implicit val ctx = context.system
    val uuid = hashing sha1 config
    val tdir = target / "extraction" / uuid
    val builds: Future[Seq[ProjectConfigAndExtracted]] = 
      Future.traverse(config.projects)(extract(tdir, log))
    // We don't have to do ordering here anymore.
    builds map RepeatableDistributedBuild.apply 
  } 

  // Our Asynchronous API.
  def extract(target: File, logger: Logger)(config: ProjectBuildConfig): Future[ProjectConfigAndExtracted] =
    (extractor ? ExtractBuildDependencies(config, target, logger.newNestedLogger(config.name))).mapTo[ProjectConfigAndExtracted]
  
  
  // TODO - Repository Knowledge here
  def buildProject(target: File, build: RepeatableProjectBuild, logger: Logger): Future[BuildArtifacts] =
    (builder ? RunBuild(target, build, logger)).mapTo[BuildArtifacts]
}