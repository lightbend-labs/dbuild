package com.typesafe.dbuild.build

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.deploy.Creds.loadCreds
import Logger.prepareLogMsg
import com.typesafe.dbuild.model._
import java.io.File
import com.typesafe.dbuild.repo.core.{ LocalRepoHelper, Repository }
import com.typesafe.dbuild.model.SeqStringH._

/**
 * Defines a task that will run before or after the build, defined somewhere
 *  in the "options" section. No result; it anything should go wrong, just throw
 *  an exception.
 */
abstract class OptionTask(log: Logger) {
  /**
   * This method is called at the very beginning of the build; it should perform
   *  a sanity check on the configuration file.
   */
  def beforeBuild(projectNames: Seq[String]): Unit
  /**
   * the afterBuild() may be called after build, if the build succeeded/failed, or
   *  after extraction, if extraction failed. In the latter case, repBuild will be
   *  null, and the OptionTask may not run, printing a message instead.
   *  For example, deploy will not run after extraction, but notifications will be
   *  sent out anyway.
   *  Similarly, BuildOutcome may be BuildBad, or may implement TimedOut; in those
   *  cases, some OptionTasks may not run, or run partially.
   */
  def afterBuild(repBuild: Option[RepeatableDBuildConfig], outcome: BuildOutcome): Unit
  /** just the task name */
  def id: String

  // This method is just a common utility for OptionTasks, which may need to
  // iterate over a list of notifications/deployment/whatever. They need
  // to try each one, and even if one fails (throwing an exception), they
  // need to continue with the following ones.
  // This method (optionally) prints a brief diagnostic message, remembers
  // whether an exception occurred, and if so rethrows an exception at the end,
  // after all the elements have been tried.
  // this mechanism can be nested arbitrarily, for convenience.
  def runRememberingExceptions[A](diagnose: Boolean, i: Iterable[A])(f: A => Unit) = {
    val status = i map { item =>
      try { f(item); (true, "") } catch {
        case e =>
          // Got an error from this notification? remember it and continue.
          // use "diagnose" to print diagnostics only in the innermost calls
          if (diagnose) prepareLogMsg(log, e)
          (false, e.getMessage)
      }
    }
    // was there an error? pass it up
    status.find(!_._1) map {
      case (_, msg) =>
        throw new Exception(msg)
    }
  }

  // utility method, used to reload artifacts from a SeqSelectorElement
  //
  // partialOK == false --> if any projects are missing, don't bother loading any of them
  // partialOK == true  --> reload what you can
  def rematerialize(request: SeqSelectorElement, outcome: BuildOutcome, build: RepeatableDBuildConfig,
    dir: File, stage: String, msgGood: String, msgBad: String, partialOK: Boolean, log: Logger) = {
    val cache = Repository.default
    val projectNames = build.builds.map { _.config.name }.toSet
    // let's expand ".":
    // flattenAndCheckProjectList() will check that the listed project names listed in the
    // deploy options exist and are used legally. From the RepeatableDBuildConfig,
    // here we only use the list of project names. 
    val selectors = request.flattenAndCheckProjectList(projectNames)
    val selected = selectors.map { sel =>
      build.repeatableBuilds.find(_.config.name == sel.name) match {
        // It should always find it: flattenAndCheckProjectList(), above, is supposed to check the same condition
        case None => sys.error("Internal error during " + stage + ": \"" + sel.name + "\" is not a project name.")
        case Some(proj) => (sel, proj) // (deploy request,RepeatableProjectBuild)
      }
    }
    val projectOutcomes = outcome.outcomes.map(o => (o.project, o)).toMap

    // It may be that certain projects listed in the Seq[RepeatableProjectBuild] have been
    // skipped, for whatever reason. If there is no outcome, the project was not built.
    val (good, bad) = selected partition {
      case (depl, proj) =>
        val optOutcome = projectOutcomes.get(proj.config.name)
        optOutcome match {
          case None =>
            log.info("No outcome for project " + proj.config.name + " (skipped)")
            false
          case Some(outcome) => outcome.isInstanceOf[BuildGood]
        }
    }

    // if partialOK:
    //   issue messages for good and bad, and try to reload the good ones
    // if not partialOK:
    //   if bad is empty, then issue message for good and reload everything
    //   if bad is not empty, then issue message for bad and do nothing else
    def reloadGood() = {
      val seqArtsModInfos = good map {
        case (depl, proj) =>
          val subprojs: Seq[String] = depl match {
            case SelectorSubProjects(SubProjects(from, publish)) => publish
            case SelectorProject(_) => Seq[String]()
          }
          val (arts, modInfos, msg) = LocalRepoHelper.materializePartialProjectRepository(proj.uuid, subprojs, cache, dir, debug = false)
          msg foreach { log.info(_) }
          (arts, modInfos)
      }
      (seqArtsModInfos.flatMap(_._1), seqArtsModInfos.flatMap(_._2))
    }
    def issueMsg(set: Set[(SelectorElement, RepeatableProjectBuild)], msg: String, l: (=> String) => Unit) =
      if (set.nonEmpty) l(msg + (set.map(_._1.name).toSeq.sorted.mkString("", ", ", "")))

    val (goodArts,goodMods) = if (partialOK) {
      issueMsg(good, msgGood, log.info)
      issueMsg(bad, msgBad, log.warn)
      reloadGood()
    } else {
      if (bad.isEmpty) {
        issueMsg(good, msgGood, log.info)
        reloadGood()
      } else {
        issueMsg(bad, msgBad, log.warn)
        (Set[ArtifactLocation](), Set[com.typesafe.dbuild.manifest.ModuleInfo]())
      }
    }
    (good, goodArts, goodMods, bad)
  }
}
