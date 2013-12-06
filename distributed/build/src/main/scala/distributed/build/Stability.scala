package distributed
package build

import sbt._
import Path._
import project.model._
import java.io.File
import distributed.repo.core.{ LocalRepoHelper, Repository }
import org.apache.commons.io.FileUtils

class Stability(options: GeneralOptions, log: logging.Logger) extends OptionTask(log) {
  def id = "Stability"

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
            def rematerializeStability(request: SeqSelectorElement, dir: File, c: String) =
              rematerialize(request, outcome, build, dir,
                stage = "stability",
                msgGood = c + ")  ",
                msgBad = c + ")  Cannot compare, unavailable: ",
                partialOK = false, log)
            val (goodA, badA) = rematerializeStability(check.a, dirA, "a")
            if (badA.isEmpty) {
              val (goodB, badB) = rematerializeStability(check.b, dirB, "b")
              if (badB.isEmpty) {
                val logLimit = 10
                // excellent! We just need to compare the jars in dirA and dirB
                val jarsA = dirA.**("*.jar").get
                val jarsB = dirB.**("*.jar").get
                def checkPaths(x: Seq[String], y: Seq[String], xName: String, yName: String) = {
                  val xNotY = x.diff(y)
                  val ok = xNotY.isEmpty
                  if (!ok) {
                    log.info("Some jars are in " + xName + " but not in " + yName + ":")
                    xNotY.take(logLimit).foreach { s =>
                      log.info("  " + s)
                    }
                    if (xNotY.length > logLimit) log.info("  ... and others")
                  }
                  ok
                }
                def getPaths(dir: File, jars: Seq[File]): Seq[String] = {
                  jars.map { IO.relativize(dir, _) getOrElse sys.error("Internal error while relativizing (stability). Please report.") }
                }
                log.info("Comparing....")
                val pathsA = getPaths(dirA, jarsA)
                val pathsB = getPaths(dirB, jarsB)
                val sameAB = checkPaths(pathsA, pathsB, "a", "b")
                val sameBA = checkPaths(pathsB, pathsA, "b", "a")
                if (!(sameAB && sameBA)) {
                  sys.error("Stability comparison failed: file lists differ")
                }
                val results = pathsA.toSet.map { p: String =>
                  val same = FileUtils.contentEquals(new File(dirA, p), new File(dirB, p))
                  // INSUFFICIENT! I need to expand the jar, unfortunately the jar compression is not deterministic
                  if (!same) Some("Files differ: " + p) else None
                }
                val badResults = results.flatten
                if (badResults.nonEmpty) {
                  badResults.take(logLimit).foreach { s =>
                    log.info("  " + s)
                  }
                  if (badResults.size > logLimit) log.info("  ... and others")
                } else log.info("Comparison OK.")
              }
            }
          }
        }
      }
      log.info("--== End Checking Stability  ==--")
    }
  }
}