package distributed.build

import distributed.project.model._
import distributed.logging.Logger
import Creds.loadCreds
import Logger.prepareLogMsg

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
  def afterBuild(repBuild: Option[RepeatableDistributedBuild], outcome: BuildOutcome): Unit
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

}
