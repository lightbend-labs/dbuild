package distributed
package build

import sbt._
import Path._
import project.model._
import java.io.File
import distributed.repo.core.{ LocalRepoHelper, Repository }

class Stability(options: GeneralOptions, log: logging.Logger) extends OptionTask(log) {
  def id = "Deploy"

  def beforeBuild(projectNames: Seq[String]) = {
    options.stability.foreach { check =>
      // initial sanity check
      check.a.flattenAndCheckProjectList(projectNames.toSet)
      check.b.flattenAndCheckProjectList(projectNames.toSet)
    }
  }

  def afterBuild(optRepBuild: Option[RepeatableDistributedBuild], outcome: BuildOutcome) = {
    def dontRun() = log.error("*** Stability cannot run: build did not complete.")
    // we do not run stability if we reached time out, or if we never arrived past extraction (or both)
    if (outcome.isInstanceOf[TimedOut]) dontRun() else
      optRepBuild match {
        case None => dontRun
        case Some(repBuild) => checkStability(options, repBuild, outcome, log)
      }
  }

  /**
   * The semantics of selection is:
   * - For each node, the set of artifacts available for deploy is: the set of artifacts of all the
   *   successful children, plus its own if successful.
   * - The root build, denoted by ".", has no artifacts of its own.
   * - This rule also applies applies to nested hierarchical build systems, if they are in turn recursive.
   */
  def checkStability(options: GeneralOptions, build: RepeatableDistributedBuild, outcome: BuildOutcome, log: logging.Logger) = {
    val stabilityChecks = options.stability
    if (stabilityChecks.nonEmpty) {
      log.info("--== Checking Stability  ==--")
      runRememberingExceptions(true, stabilityChecks) { check =>
        IO.withTemporaryDirectory { dirA =>
          IO.withTemporaryDirectory { dirB =>
            log.info("Checking pair:")
            def rematerializeStability(request: SeqSelectorElement, dir: File) =
              rematerialize(request, outcome, build, dir,
                stage = "stability",
                msgGood = "  ",
                msgBad = "  Cannot compare, unavailable: ",
                partialOK = false, log)
            val (goodA, badA) = rematerializeStability(check.a, dirA)
            if (badA.isEmpty) {
              val (goodB, badB) = rematerializeStability(check.b, dirB)
              if (badB.isEmpty) {
                // excellent! We just need to compare the jars in dirA and dirB
                
              }
            }
          }
        }
      }
      log.info("--== End Checking Stability  ==--")
    }
  }
}