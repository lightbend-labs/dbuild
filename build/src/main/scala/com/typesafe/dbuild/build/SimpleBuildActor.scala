package com.typesafe.dbuild.build

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.build._
import com.typesafe.dbuild.repo.core.{ Repository, LocalRepoHelper }
import com.typesafe.dbuild.project.dependencies.ExtractBuildDependencies
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.hashing
import com.typesafe.dbuild.graph
import com.typesafe.dbuild.project.Timeouts
import akka.actor.{ Actor, ActorRef, Props }
import akka.pattern.{ ask, pipe, after }
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise, ExecutionContext }
import ExecutionContext.Implicits.global
import akka.util.Timeout
import sbt.Path._
import java.io.File
import com.typesafe.dbuild.repo.core.GlobalDirs
import org.apache.maven.execution.BuildFailure
import Logger.prepareLogMsg
import com.typesafe.dbuild.model.SeqDBCH._

case class RunDBuild(conf: DBuildConfiguration, confName: String,
  buildTarget: Option[String], logger: Logger, options: BuildRunOptions)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef, repository: Repository, systems: Seq[BuildSystemCore]) extends Actor {
  val extractionDuration = Timeouts.extractionTimeout.duration
  val extractionPhaseDuration = Timeouts.extractionPhaseTimeout.duration
  val extractionPlusBuildDuration = Timeouts.extractionPlusBuildTimeout.duration
  val buildDuration = Timeouts.buildTimeout.duration
  @inline
  final def forwardingErrorsToFutures[A](sender: ActorRef)(f: => A): A =
    try f catch {
      case e: Exception =>
        sender ! akka.actor.Status.Failure(e)
        throw e
    }

  def receive = {
    case RunDBuild(inputConf, confName, buildTarget, log, options) => forwardingErrorsToFutures(sender) {
      val listener = sender
      implicit val ctx = context.system
      val logger = log.newNestedLogger(hashing sha1 inputConf)
      //
      // Before doing anything else, expand all the "extra" fields in the project configs,
      // replacing in the process the default values as needed.
      val projects = BuildSystem.expandDBuildConfig(inputConf.build, systems)
      val generalOptions = inputConf.options
      //  After this point, inputConf and its BuildOptions are never used again.
      //
      val result = try {
        val notifTask = new Notifications(options.defaultNotifications, generalOptions, confName, log)
        // add further new tasks at the beginning of this list, leave notifications at the end
        val tasks: Seq[OptionTask] = Seq(new DeployBuild(generalOptions, log), new Comparison(generalOptions, log), notifTask)
        val projectNames = projects.map { _.name }
        tasks foreach { _.beforeBuild(projectNames) }
        // afterTasks may be called when the build is complete, or when something went wrong.
        // Some tasks (for instance deploy) may be unable to run when some error conditions
        // occurred. Each should detect the error conditions; what we offer for them to check is:
        // - rdb is a RepeatableDBuildConfig if we completed extraction successfully, and
        // we got to building. If we stopped before building (during extraction, or immediately
        // afterward), rdb will be None.
        // - futureBuildResult is the eventual BuildOutcome. In particular, tasks may test whether
        // the outcome is an instance of TimedOut, which may mean that we were interrupted and
        // therefore the generated data may be incomplete, corrupted, or absent. Each OptionTask
        // may decide not to run, or to run partially, if the outcome is one of TimedOut, BuildBad, etc.
        def afterTasks(rdb: Option[RepeatableDBuildConfig], futureBuildResult: Future[BuildOutcome]): Future[BuildOutcome] = {
          if (tasks.nonEmpty) futureBuildResult map {
            // >>>> careful with map() on Futures: exceptions must be caught separately!!
            wrapExceptionIntoOutcome[BuildOutcome](log) { buildOutcome =>
              val taskOuts = {
                val taskOutsWithoutNotif = tasks.diff(Seq(notifTask)) map { t =>
                  try { // even if one task fails, we move on to the rest
                    t.afterBuild(rdb, buildOutcome)
                    (t.id, true)
                  } catch {
                    case e:Throwable =>
                      (t.id, false)
                  }
                }
                // generate an outcome that Notifications can use, with a report from all other tasks
                val badForNotif = taskOutsWithoutNotif.filter(!_._2).map { _._1 }
                val outcomeForNotif = if (badForNotif.nonEmpty)
                  TaskFailed(".", buildOutcome.outcomes, buildOutcome, "Tasks failed: " + badForNotif.mkString(", "))
                else
                  buildOutcome
                // ok, let's concatenate the previous task results with the one from notifications, and proceed with the
                // calculation of the final global outcome and the final tasks reporting (and possibly an emergency notification,
                // if that is ever implemented).
                taskOutsWithoutNotif :+ (try {
                  notifTask.afterBuild(rdb, outcomeForNotif)
                  (notifTask.id, true)
                } catch {
                  case e:Throwable =>
                    (notifTask.id, false)
                })
              }
              log.info("---==  Tasks Report ==---")
              val (good, bad) = taskOuts.partition(_._2)
              if (good.nonEmpty) log.info(good.map(_._1).mkString("Successful: ", ", ", ""))
              if (bad.nonEmpty) log.info(bad.map(_._1).mkString("Failed: ", ", ", ""))
              log.info("---==  End Tasks Report ==---")
              if (bad.nonEmpty)
                TaskFailed(".", buildOutcome.outcomes, buildOutcome, "Tasks failed: " + bad.map(_._1).mkString(", "))
              else
                buildOutcome
            }
          }
          else futureBuildResult
        }
        // "conf" contains the project configs as written in the configuration file.
        // Their 'extra' field could be None, or contain information that must be completed
        // according to the build system in use for that project.
        // Only each build system knows its own defaults (which may change over time),
        // therefore we have to ask to the build system itself to expand the 'extra' field
        // as appropriate.
        //

        // A few watchdogs and timeout limits are used; please refer to object "Timeouts" for details.
        //
        // Both the extraction and the build of a single project may time out. The handling of time outs,
        // however, is very tricky. Extraction and build may spawn an external process (for example in the
        // case of sbt), which needs to be killed in case of timeout, otherwise it will just keep working
        // indefinitely. Also, Akka actors cannot be killed preemptively, and neither can Akka futures.
        // All that makes it very impractical to try and catch timeouts, in order to present a clean report
        // at the end of the run detailing what exactly timed out, while allowing the remaining projects
        // to progress normally.
        // Because of these reasons, all timeouts are made fatal: as soon as a timeout condition is detected,
        // dbuild will generate a TimeoutException which will be let to propagate up to the dbuild main.
        // However, there will still be an attempt to run a trimmed-down notification stage, but no other
        // afterTasks will be attempted.
        //
        // Also: extractions and buildings will be scheduled for execution, but their actual starting time
        // may happen much later. For instance, if we have many independent projects that do rely on other
        // projects for their dependencies, they will all be send to the builder actor pool together, but
        // some may start only later, depending on the pool capacity. Because of this reason the extraction
        // and build timeouts have to be applied within the extractor and the builder, respectively, and
        // not here.

        val extractionWatchdog = after(extractionPhaseDuration,
          using = ctx.scheduler)(
            Future(new ExtractionFailed(".", Seq(), "Timeout: extraction took longer than " + extractionPhaseDuration) with TimedOut))
        val extractionPlusBuildWatchdog = after(extractionPlusBuildDuration,
          using = ctx.scheduler) {
            // something went wrong and we ran into an unexpected timeout. We prepare the watchdog at the beginning,
            // so that we know we will have enough time for the notifications before dbuildTimeout arrives. So we mark
            // this as a BuildFailed; we also create a fictitious dependency on every subproject, where every
            // subproject also fails in the same manner.
            val msg = "Timeout: extraction plus building took longer than " + extractionPlusBuildDuration
            def timeoutOutcome(name: String) = BuildFailed(name, Seq(), msg)
            val outcomes = projects.map { p => timeoutOutcome(p.name) }
            Future(new BuildFailed(".", outcomes, msg) with TimedOut)
          }

        val extractionOutcome = analyze(projects.sortBy(_.name.toLowerCase), log.newNestedLogger(hashing sha1 projects), options.debug)
        Future.firstCompletedOf(Seq(extractionWatchdog, extractionOutcome)) flatMap {
          wrapExceptionIntoOutcomeF[ExtractionOutcome](log) {
            case extractionOutcome: ExtractionFailed =>
              // This is a bit of a hack, in order to get better notifications: we
              // replace extractionOutcome.outcomes.outcomes so that, for each extraction
              // that was ok in extractionOutcome.outcomes, a fictitious dependency is
              // created in order to point out that we could not proceed due to some
              // other failing extraction, and we list which ones.
              val remappedExtractionOutcome = extractionOutcome.copy(outcomes =
                extractionOutcome.outcomes.map(o => if (o.isInstanceOf[ExtractionOK]) o.withOutcomes(extractionOutcome.outcomes.diff(Seq(o))) else o))
              afterTasks(None, Future(remappedExtractionOutcome))
            case extractionOutcome: ExtractionOK =>

              // fromExtractionOutcome() may fail, for instance if cycles are detected. Possibly,
              // something may also go wrong within publishFullBuild(). So, we must catch and wrap exceptions
              // for those as well, and run afterTask() as usual afterward.
              def nest[K](f: => K)(g: K => Future[BuildOutcome]): Future[BuildOutcome] = (try {
                Left(f)
              } catch {
                case e:Throwable =>
                  val outcome = Future(ExtractionFailed(".", extractionOutcome.outcomes, "Cause: " + prepareLogMsg(log, e)))
                  afterTasks(None, outcome)
                  Right(outcome)
              }) match {
                case Left(k) => g(k)
                case Right(o) => o
              }

              nest(RepeatableDBuildConfigH.fromExtractionOutcome(extractionOutcome)) { fullBuild =>
                // what we call "RepeatableDBuildConfig" is actually only the portion of data that
                // affect the build. Further options that do /not/ affect the build, but control dbuild in
                // other ways (notifications, resolvers, etc), are in the GeneralOptions.
                // In order to present to the user a new complete configuration that can be used as-is to
                // restart dbuild, and which contains the RepeatableDBuildConfig data, we create
                // a new full DBuildConfiguration.
                val expandedDBuildConfig = DBuildConfiguration(Seq(fullBuild.repeatableBuildConfig), generalOptions)
                val fullLogger = log.newNestedLogger(expandedDBuildConfig.uuid)
                writeDependencies(fullBuild, fullLogger)
                nest(publishFullBuild(SavedConfiguration(expandedDBuildConfig, fullBuild), fullLogger)) { unit =>
                  // are we building a specific target? If so, filter the graph
                  // are we building a specific target? If so, filter the graph
                  val targetGraph = filterGraph(buildTarget, fullBuild)
                  val findBuild = fullBuild.buildMap
                  val futureBuildResult = runBuild(targetGraph, findBuild, expandedDBuildConfig.uuid,
                    fullLogger, options.debug)
                  afterTasks(Some(fullBuild), Future.firstCompletedOf(Seq(extractionPlusBuildWatchdog, futureBuildResult)))
                }
              }

            case _ => sys.error("Internal error: extraction did not return ExtractionOutcome. Please report.")
          }
        }
      } catch {
        case e:Throwable =>
          Future(UnexpectedOutcome(".", Seq.empty, "Cause: " + prepareLogMsg(log, e)))
      }
      result pipeTo listener
    }
  }

  final def wrapExceptionIntoOutcomeFS[A <: Seq[BuildOutcome]](log: Logger)(f: A => Future[BuildOutcome])(a: A): Future[BuildOutcome] = {
    implicit val ctx = context.system
    try f(a) catch {
      case e:Throwable =>
        Future(UnexpectedOutcome(".", a, "Cause: " + prepareLogMsg(log, e)))
    }
  }
  final def wrapExceptionIntoOutcomeF[A <: BuildOutcome](log: Logger)(f: A => Future[BuildOutcome])(a: A): Future[BuildOutcome] = {
    implicit val ctx = context.system
    try f(a) catch {
      case e:Throwable =>
        Future(UnexpectedOutcome(".", a.outcomes, "Cause: " + prepareLogMsg(log, e)))
    }
  }
  final def wrapExceptionIntoOutcome[A <: BuildOutcome](log: Logger)(f: A => BuildOutcome)(a: A): BuildOutcome = {
    try f(a) catch {
      case e:java.util.concurrent.TimeoutException =>
        new BuildFailed(".", a.outcomes, "Timeout: took longer than the allowed time limit.") with TimedOut
      case e:Throwable =>
        UnexpectedOutcome(".", a.outcomes, "Cause: " + prepareLogMsg(log, e))
    }
  }


  /**
   * Publishing the full build to the repository and logs the output for
   * re-use.
   */
  def publishFullBuild(saved: SavedConfiguration, log: Logger): Unit = {
    log.info("---==  Repeatable Build Info ==---")
    log.info(" uuid = " + saved.uuid)
    log.info("---== Repeatable dbuild Configuration ===---")
    log.info("You can repeat this build (except for -SNAPSHOT references) using this configuration:\n" +
      Utils.writeValueFormatted(saved.expandedDBuildConfig))
    log.info("---== End Repeatable dbuild Configuration ===---")
    log.info("---== Writing dbuild Metadata ===---")
    LocalRepoHelper.publishBuildMeta(saved, repository, log)
    log.info("---== End Writing dbuild Metadata ===---")
    log.info("---==  End Repeatable Build Info ==---")
  }

  def writeDependencies(build: RepeatableDBuildConfig, log: Logger) = {
    log.info("---== Dependency Information ===---")
    build.repeatableBuilds foreach { b =>
      log.info("Project " + b.config.name)
      /*
      //
      // see note on ghost dependencies in class RepeatableDBuildConfig;
      // the (commented) algorithm below is disabled as it would print those
      // ghost dependencies as well, which may confuse the end user
      //
      // to print dependencies:
      // - check if any level beyond the first had a non-empty list of deps
      // - if not, print a simple one-liner list
      // - if yes, print the range from the first to the last level that is non-empty,
      //   prefixing with an id or number to indicate the plugin level
      val last = b.depInfo.lastIndexWhere(_.dependencyNames.nonEmpty)
      if (last < 1)
        // also print this line if no dependencies
        log.info((b.depInfo.head.dependencyNames) mkString ("  depends on: ", ", ", ""))
      else {
        (0 to last) zip b.depInfo foreach { case (i,d) =>
          log.info(d.dependencyNames mkString ("  level "+i+" depends on: ", ", ", ""))
        }
      }
      */
      log.info((b.depInfo.flatMap(_.dependencyNames).distinct) mkString ("  depends on: ", ", ", ""))
    }
    log.info("---== End Dependency Information ===---")
  }

  type ProjectGraph = graph.Graph[ProjectConfigAndExtracted, EdgeData]
  type BuildFinder = Function1[String, RepeatableProjectBuild]

  def filterGraph(buildTarget: Option[String], fullBuild: RepeatableDBuildConfig): ProjectGraph = {
    import fullBuild.graph
    buildTarget match {
      case None => graph
      case Some(target) =>
        // more than one target? Find the list:
        val targets = target.split(",").toSeq
        val targetNodes = targets map { t =>
          graph.nodeForName(t) getOrElse
            sys.error("The selected target project " + t + " was not found in this configuration file.")
        }
        graph.FilteredByNodesGraph(graph.subGraphFrom(targetNodes))
    }
  }

  def runBuild(targetGraph: ProjectGraph, findBuild: BuildFinder, uuid: String,
    log: Logger, debug: Boolean): Future[BuildOutcome] = {
    implicit val ctx = context.system
    val tdir = GlobalDirs.targetDir
    type State = Future[BuildOutcome]
    def runBuild(): Seq[State] = {
      targetGraph.traverse { (children: Seq[State], p: ProjectConfigAndExtracted) =>
        val b = findBuild(p.config.name)
        Future.sequence(children) flatMap
          // excess of caution? In theory all Future.sequence()s
          // should be wrapped, but in practice even if we receive
          // an exception here (inside the sequence(), but before
          // the builder ? .., it means something /truly/ unusual
          // happened, and getting an exception might be appropriate.
          // Let's wrap anyway.
          wrapExceptionIntoOutcomeFS[Seq[BuildOutcome]](log) { outcomes =>
            if (outcomes exists { _.isInstanceOf[BuildBad] }) {
              Future(BuildBrokenDependency(b.config.name, outcomes))
            } else {
              val outProjects = p.extracted.projects
              val buildOutcome = buildProject(b, outProjects, outcomes,
                BuildData(log.newNestedLogger(b.config.name, b.config.name), debug)) // logger will print the project name
              buildOutcome
            }
        }
      }(Some((a, b) => a.config.name < b.config.name))
    }
    // we go from a Seq[Future[BuildOutcome]] to a Future[Seq[BuildOutcome]]
    Future.sequence(runBuild()).map { outcomes =>
      if (outcomes exists { case _: BuildBad => true; case _ => false })
        // "." is the name of the root project
        BuildFailed(".", outcomes,
          // pick the ones that failed, but not because any of their dependencies failed.
          outcomes.filter { o =>
            o.isInstanceOf[BuildBad] &&
              !o.outcomes.exists { _.isInstanceOf[BuildBad] }
          }.map { _.project }.mkString("failed: ", ", ", ""))
      else {
        BuildSuccess(".", outcomes, BuildArtifactsOut(Seq.empty))
      }
    }
  }

  // Asynchronously extract information from builds.
  def analyze(projects: Seq[ProjectBuildConfig], log: Logger, debug: Boolean): Future[ExtractionOutcome] = {
    implicit val ctx = context.system
    val uuid = hashing sha1 projects
    val futureOutcomes: Future[Seq[ExtractionOutcome]] =
      Future.traverse(projects) { projConfig =>
        val extractionOutcome = extract(uuid, log, debug)(ExtractionConfig(projConfig))
        extractionOutcome
      }
    futureOutcomes map { s: Seq[ExtractionOutcome] =>
      if (s exists { _.isInstanceOf[ExtractionFailed] })
        ExtractionFailed(".", s,
          s.filter { _.isInstanceOf[ExtractionFailed] }.map { _.project }.mkString("failed: ", ", ", ""))
      else {
        val sok = s.collect({ case e: ExtractionOK => e })
        ExtractionOK(".", sok, sok flatMap { _.pces })
      }
    }
  }

  // extractor and builder will manage their own timeouts, always returning a future within their allowed timeout.
  // therefore, below we use a very long timeout (just in case), which in theory should never be reached.
  // we set that to the entirety of the build process; a watchdog, above, will intervene beforehand, though.

  // Our Asynchronous API.
  def extract(uuidDir: String, logger: Logger, debug: Boolean)(config: ExtractionConfig): Future[ExtractionOutcome] =
    (extractor ? ExtractBuildDependencies(config, uuidDir,     // the logger will print the project name
        logger.newNestedLogger(config.buildConfig.name, config.buildConfig.name), debug))(extractionPlusBuildDuration).mapTo[ExtractionOutcome]

  // TODO - Repository Knowledge here
  // outProjects is the list of Projects that will be generated by this build, as reported during extraction.
  // we will need it to calculate the version string in LocalBuildRunner, but won't need it any further
  def buildProject(build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], buildData: BuildData): Future[BuildOutcome] =
    (builder ? RunBuild(build, outProjects, children, buildData))(extractionPlusBuildDuration).mapTo[BuildOutcome]
}
