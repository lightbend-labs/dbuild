package distributed
package build

import project.model._
import project.build._
import repo.core.{ Repository, LocalRepoHelper }
import project.dependencies.ExtractBuildDependencies
import support.BuildSystemCore
import project.{ BuildSystem, BuildData }
import logging.Logger
import akka.actor.{ Actor, ActorRef, Props }
import akka.pattern.{ ask, pipe }
import akka.dispatch.{ Future, Futures, Promise }
import akka.util.duration._
import akka.util.Timeout
import actorpatterns.forwardingErrorsToFutures
import sbt.Path._
import java.io.File
import distributed.repo.core.ProjectDirs
import org.apache.maven.execution.BuildFailure
import Logger.prepareLogMsg

case class RunDistributedBuild(conf: DBuildConfiguration, confName: String,
  buildTarget: Option[String], logger: Logger, options: BuildRunOptions)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef, repository: Repository, systems: Seq[BuildSystemCore]) extends Actor {
  def receive = {
    case RunDistributedBuild(inputConf, confName, buildTarget, log, options) => forwardingErrorsToFutures(sender) {
      val listener = sender
      implicit val ctx = context.system
      val extractionPhaseDuration = Timeouts.extractionPhaseTimeout.duration
      val extractionPlusBuildDuration = Timeouts.extractionPlusBuildTimeout.duration
      val logger = log.newNestedLogger(hashing sha1 inputConf)
      //
      // Before doing anything else, expand all the "extra" fields in the project configs,
      // replacing in the process the default values as needed.
      val projects = BuildSystem.expandDistributedBuildConfig(inputConf.build, systems)
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
        // - rdb is a RepeatableDistributedBuild if we completed extraction successfully, and
        // we got to building. If we stopped before building (during extraction, or immediately
        // afterward), rdb will be None.
        // - futureBuildResult is the eventual BuildOutcome. In particular, tasks may test whether
        // the outcome is an instance of TimedOut, which may mean that we were interrupted and
        // therefore the generated data may be incomplete, corrupted, or absent. Each OptionTask
        // may decide not to run, or to run partially, if the outcome is one of TimedOut, BuildBad, etc.
        def afterTasks(rdb: Option[RepeatableDistributedBuild], futureBuildResult: Future[BuildOutcome]): Future[BuildOutcome] = {
          if (tasks.nonEmpty) futureBuildResult map {
            // >>>> careful with map() on Futures: exceptions must be caught separately!!
            wrapExceptionIntoOutcome[BuildOutcome](log) { buildOutcome =>
              val taskOuts = {
                val taskOutsWithoutNotif = tasks.diff(Seq(notifTask)) map { t =>
                  try { // even if one task fails, we move on to the rest
                    t.afterBuild(rdb, buildOutcome)
                    (t.id, true)
                  } catch {
                    case e =>
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
                  case e =>
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

        // I use four watchdogs; please refer to object "Timeouts" for details. Please make
        // sure that any outcome returned by a watchdog extends TimedOut (the condition is
        // checked within afterTask() to inhibit deployment and possibly other tasks that
        // can only be executed when building completed successfully).
        val extractionWatchdog = Timeouts.after(extractionPhaseDuration,
          using = ctx.scheduler)(
            Future(new ExtractionFailed(".", Seq(), "Timeout: extraction took longer than " + extractionPhaseDuration) with TimedOut))
        val extractionPlusBuildWatchdog = Timeouts.after(extractionPlusBuildDuration,
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

        val extractionOutcome = analyze(projects, log.newNestedLogger(hashing sha1 projects), options.debug)
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
                case e =>
                  val outcome = Future(ExtractionFailed(".", extractionOutcome.outcomes, "Cause: " + prepareLogMsg(log, e)))
                  afterTasks(None, outcome)
                  Right(outcome)
              }) match {
                case Left(k) => g(k)
                case Right(o) => o
              }

              nest(RepeatableDistributedBuild.fromExtractionOutcome(extractionOutcome)) { fullBuild =>
                // what we call "RepeatableDistributedBuild" is actually only the portion of data that
                // affect the build. Further options that do /not/ affect the build, but control dbuild in
                // other ways (notifications, resolvers, etc), are in the GeneralOptions.
                // In order to present to the user a new complete configuration that can be used as-is to
                // restart dbuild, and which contains the RepeatableDistributedBuild data, we create
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
        case e =>
          Future(UnexpectedOutcome(".", Seq.empty, "Cause: " + prepareLogMsg(log, e)))
      }
      result pipeTo listener
    }
  }

  final def wrapExceptionIntoOutcomeF[A <: BuildOutcome](log: logging.Logger)(f: A => Future[BuildOutcome])(a: A): Future[BuildOutcome] = {
    implicit val ctx = context.system
    try f(a) catch {
      case e =>
        Future(UnexpectedOutcome(".", a.outcomes, "Cause: " + prepareLogMsg(log, e)))
    }
  }
  final def wrapExceptionIntoOutcome[A <: BuildOutcome](log: logging.Logger)(f: A => BuildOutcome)(a: A): BuildOutcome = {
    try f(a) catch {
      case e =>
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

  def writeDependencies(build: RepeatableDistributedBuild, log: Logger) = {
    log.info("---== Dependency Information ===---")
    build.repeatableBuilds foreach { b =>
      log.info("Project " + b.config.name)
      log.info(b.depInfo flatMap (_.dependencyNames) mkString ("  depends on: ", ", ", ""))
    }
    log.info("---== End Dependency Information ===---")
  }

  def logPoms(build: RepeatableDistributedBuild, arts: BuildArtifactsIn, log: Logger): Unit =
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
  type ProjectGraph = graph.Graph[ProjectConfigAndExtracted, Nothing]
  type BuildFinder = Function1[String, RepeatableProjectBuild]
  
  def filterGraph(buildTarget: Option[String], fullBuild: RepeatableDistributedBuild): ProjectGraph = {
    import fullBuild.graph
    buildTarget match {
      case None => graph
      case Some(target) =>
        graph.FilteredByNodesGraph(graph.subGraphFrom(graph.nodeForName(target) getOrElse
          sys.error("The selected target project " + target + " was not found in this configuration file.")))
      // if you want to extend buildTarget to make it a SeqString, just use this instead:
      // graph.FilteredByNodesGraph(targets.toSet.flatMap { p: String => graph.subGraphFrom(graph.nodeForName(p)) })
    }
  }

  def runBuild(targetGraph: ProjectGraph, findBuild: BuildFinder, uuid: String,
      log: Logger, debug: Boolean): Future[BuildOutcome] = {
    implicit val ctx = context.system
    val tdir = ProjectDirs.targetDir
    type State = Future[BuildOutcome]
    def runBuild(): Seq[State] = {
      targetGraph.traverse { (children: Seq[State], p: ProjectConfigAndExtracted) =>
        val b = findBuild(p.config.name)
        Future.sequence(children) flatMap {
          // excess of caution? In theory all Future.sequence()s
          // should be wrapped, but in practice even if we receive
          // an exception here (inside the sequence(), but before
          // the builder ? .., it means something /truly/ unusual
          // happened, and getting an exception is appropriate.
          //   wrapExceptionIntoOutcome[Seq[BuildOutcome]](log) { ...
          outcomes =>
            if (outcomes exists { _.isInstanceOf[BuildBad] }) {
              Future(BuildBrokenDependency(b.config.name, outcomes))
            } else {
              val outProjects = p.extracted.projects
              val buildDuration = Timeouts.buildTimeout.duration
              val watchdog = Timeouts.after(buildDuration,
                using = ctx.scheduler)(
                  Future(new BuildFailed(b.config.name, outcomes,
                    "Timeout: building project " + b.config.name + " took longer than " + buildDuration) with TimedOut))
              val buildOutcome = buildProject(b, outProjects, outcomes,
                BuildData(log.newNestedLogger(b.config.name), debug))
              Future.firstCompletedOf(Seq(watchdog, buildOutcome))
            }
        }
      }(Some((a, b) => a.config.name < b.config.name))
    }
    // TODO - REpository management here!!!!
    ProjectDirs.userRepoDirFor(uuid) { localRepo =>
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
  }

  // Asynchronously extract information from builds.
  def analyze(projects: Seq[ProjectBuildConfig], log: Logger, debug: Boolean): Future[ExtractionOutcome] = {
    implicit val ctx = context.system
    val uuid = hashing sha1 projects
    val futureOutcomes: Future[Seq[ExtractionOutcome]] =
      Future.traverse(projects) { projConfig =>
        val extractionDuration = Timeouts.extractionTimeout.duration
        val watchdog = Timeouts.after(extractionDuration,
          using = ctx.scheduler)(
            Future(new ExtractionFailed(projConfig.name, Seq(),
              "Timeout: extraction of project " + projConfig.name + " took longer than " + extractionDuration) with TimedOut))
        val outcome =
          extract(uuid, log, debug)(ExtractionConfig(projConfig))
        Future.firstCompletedOf(Seq(watchdog, outcome))
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

  // Our Asynchronous API.
  def extract(uuidDir: String, logger: Logger, debug: Boolean)(config: ExtractionConfig): Future[ExtractionOutcome] =
    (extractor ? ExtractBuildDependencies(config, uuidDir, logger.newNestedLogger(config.buildConfig.name), debug)).mapTo[ExtractionOutcome]

  // TODO - Repository Knowledge here
  // outProjects is the list of Projects that will be generated by this build, as reported during extraction.
  // we will need it to calculate the version string in LocalBuildRunner, but won't need it any further 
  def buildProject(build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], buildData:BuildData): Future[BuildOutcome] =
    (builder ? RunBuild(build, outProjects, children, buildData)).mapTo[BuildOutcome]
}
