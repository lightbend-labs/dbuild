package com.typesafe.dbuild.build

import java.io.File
import akka.actor.{ ActorSystem, Props }
import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.AskTimeoutException
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Success,Failure}
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.model.Utils.{ readValue, writeValue }
import com.typesafe.dbuild.repo.core._
import com.typesafe.dbuild.model.ClassLoaderMadness
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.logging.Logger.prepareLogMsg
import akka.pattern.ask
import com.typesafe.dbuild.repo.core.GlobalDirs.checkForObsoleteDirs
import com.typesafe.dbuild.support
import com.typesafe.dbuild.logging
import akka.actor.{DeadLetter, Actor}
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.utils.TrackedProcessBuilder
import scala.concurrent.ExecutionContext.Implicits.global
import com.typesafe.config.ConfigFactory

class DeadLetterMonitorActor(logger: Logger)
  extends Actor {
  logger.debug("DeadLetterMonitorActor: now monitoring")

  def receive = {

    case DeadLetter(message, snd, rcp) =>
      val origin = if (snd eq context.system.deadLetters) "without sender" else s"from $snd"
      logger.debug(s"Dead Letter! Message [${message.getClass.getName}] $origin to $rcp was not delivered.")

    case _ => logger.info("DeadLetterMonitorActor : got an unexpected message")
  }
}

class LocalBuildMain(repos: List[xsbti.Repository], options: BuildRunOptions) {

  val targetDir = GlobalDirs.targetDir
  val resolvers = Seq(
    new support.git.GitProjectResolver(options.skipGitUpdates),
    new support.svn.SvnProjectResolver,
    new support.ivy.IvyProjectResolver(repos),
    new support.test.TestProjectResolver,
    new support.nil.NilProjectResolver,
    new support.aether.AetherProjectResolver(repos))
  val buildSystems: Seq[BuildSystemCore] =
    Seq(new support.sbt.SbtBuildSystem(repos, targetDir, options.debug),
      support.scala.ScalaBuildSystem,
      new support.ivy.IvyBuildSystem(repos, targetDir),
      support.assemble.AssembleBuildSystem,
      support.test.TestBuildSystem,
      support.nil.NilBuildSystem,
      new support.aether.AetherBuildSystem(repos, targetDir))

  // Gymnastics for classloader madness

  val system = ClassLoaderMadness.withContextLoader(getClass.getClassLoader) {
  val conf = ConfigFactory.parseString("akka.log-dead-letters-during-shutdown: off\n" +
                                       "akka.log-dead-letters: off" )
    ActorSystem.create("dbuild", conf)
  }
  val logMgr = {
    val mgr = system.actorOf(Props(new logging.ChainedLoggerSupervisorActor), "ChainedLoggerSupervisorActor")
    val logDirManagerActor = (mgr ? Props(new logging.LogDirManagerActor(new File(targetDir, "logs"))))(1 minute)
    val systemOutLoggerActor = (mgr ? Props(new logging.SystemOutLoggerActor(options.debug)))(1 minute)
    mgr
  }
  val repository = Repository.default
  val logger = new logging.ActorLogger(logMgr)
  checkForObsoleteDirs(logger.warn _)

  val deadLetterMonitorActor = system.actorOf(Props(classOf[DeadLetterMonitorActor], logger), "deadlettermonitoractor")
  system.eventStream.subscribe(deadLetterMonitorActor, classOf[DeadLetter])

  val builder = system.actorOf(Props(new LocalBuilderActor(resolvers, buildSystems, repository, targetDir, logger, options)))
  // TODO - Look up target elsewhere...

  def build(conf: DBuildConfiguration, confName: String, buildTarget: Option[String]): BuildOutcome = {
    val dbuildtimeout = options.timeouts.dbuildTimeout
    val result1 = (builder ? RunLocalBuild(conf, confName, buildTarget))(dbuildtimeout)
    val result2 = result1.recover {
        case e: AskTimeoutException =>
          new UnexpectedOutcome(".", Seq(), "Timeout: the entire dbuild run took longer than " + dbuildtimeout) with TimedOut
        case e: Throwable =>
          new UnexpectedOutcome(".", Seq(), "Cause: " + prepareLogMsg(logger, e))
    }
    Await.result(result2.mapTo[BuildOutcome], Duration.Inf)
  }
  def dispose(): Unit = {
    TrackedProcessBuilder.abortAll()
    implicit val timeout: Timeout = 3.minutes
    Await.result((logMgr ? "exit").mapTo[String], Duration.Inf)
    system.shutdown() // pro forma, as all loggers should already be stopped at this point
    try {
      println("Shutting down, please wait...")
      system.awaitTermination(4.minute)
    } catch {
      case e:Exception => println("Warning: system did not shut down within the allotted time")
    }
  }
}
