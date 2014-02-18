package distributed
package build

import sbt._
import Path._
import project.model._
import java.io.File
import distributed.repo.core.{ LocalRepoHelper, Repository }
import org.apache.commons.io.{ FileUtils, IOUtils }
import collection.JavaConversions._
import java.util.jar.JarInputStream
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.io.FileInputStream
import org.apache.oro.text.regex

class Comparison(options: GeneralOptions, log: logging.Logger) extends OptionTask(log) {
  def id = "Comparison"

  def beforeBuild(projectNames: Seq[String]) = {
    options.comparison.foreach { check =>
      // initial sanity checks; discard the results
      check.a.flattenAndCheckProjectList(projectNames.toSet)
      check.b.flattenAndCheckProjectList(projectNames.toSet)
      check.skip.map(new org.apache.oro.text.GlobCompiler().compile(_))
    }
  }

  def afterBuild(optRepBuild: Option[RepeatableDistributedBuild], outcome: BuildOutcome) = {
    def dontRun() = log.error("*** Comparison cannot run: build did not complete.")
    // we do not run comparison if we reached time out, or if we never arrived past extraction (or both)
    if (outcome.isInstanceOf[TimedOut]) dontRun() else
      optRepBuild match {
        case None => dontRun
        case Some(repBuild) => checkComparison(options, repBuild, outcome, log)
      }
  }

  /**
   * The semantics of selection is:
   * - For each node, the set of artifacts available for deploy is: the set of artifacts of all the
   *   successful children, plus its own if successful.
   * - The root build, denoted by ".", has no artifacts of its own.
   * - This rule also applies applies to nested hierarchical build systems, if they are in turn recursive.
   */
  def checkComparison(options: GeneralOptions, build: RepeatableDistributedBuild, outcome: BuildOutcome, log: logging.Logger) = {
    val comparisonChecks = options.comparison
    if (comparisonChecks.nonEmpty) {
      log.info("--== Performing Comparison  ==--")
      runRememberingExceptions(true, comparisonChecks) { check =>
        IO.withTemporaryDirectory { dirA =>
          IO.withTemporaryDirectory { dirB =>
            log.info("Checking pair:")
            def rematerializeComparison(request: SeqSelectorElement, dir: File, c: String) =
              rematerialize(request, outcome, build, dir,
                stage = "comparison",
                msgGood = c + ")  ",
                msgBad = c + ")  Cannot compare, unavailable: ",
                partialOK = false, log)
            val (goodA, badA) = rematerializeComparison(check.a, dirA, "a")
            if (badA.isEmpty) {
              val (goodB, badB) = rematerializeComparison(check.b, dirB, "b")
              if (badB.isEmpty) {
                val logLimit = 10
                // excellent! We just need to compare the jars in dirA and dirB
                val jarsA = dirA.**("*.jar").get
                val jarsB = dirB.**("*.jar").get
                def checkPaths(x: Seq[String], y: Seq[String], xName: String, yName: String) = {
                  val xNotY = x.diff(y)
                  val ok = xNotY.isEmpty
                  if (!ok) {
                    log.error("Some jars are in " + xName + " but not in " + yName + ":")
                    xNotY.take(logLimit).foreach { s =>
                      log.error("  " + s)
                    }
                    if (xNotY.length > logLimit) log.error("  ... and others")
                  }
                  ok
                }
                def getPaths(dir: File, jars: Seq[File]): Seq[String] = {
                  jars.map { IO.relativize(dir, _) getOrElse sys.error("Internal error while relativizing (comparison). Please report.") }
                }
                log.info("Comparing....")
                val pathsA = getPaths(dirA, jarsA)
                val pathsB = getPaths(dirB, jarsB)
                val sameAB = checkPaths(pathsA, pathsB, "a", "b")
                val sameBA = checkPaths(pathsB, pathsA, "b", "a")
                if (!(sameAB && sameBA)) {
                  sys.error("Comparison failed: file lists differ")
                }
                // The standard Java regex alternative would be:
                // val skipMatchers = check.skip.map(java.util.regex.Pattern.compile(_).matcher(""))
                // ... || skipMatchers.exists(_.reset(name).matches)
                // But globs are a bit easier to use in this context.
                val skipGlobPatterns = check.skip.map(new org.apache.oro.text.GlobCompiler().compile(_))
                val matcher = new regex.Perl5Matcher()
                pathsA.foreach { p: String =>
                  val fa = new File(dirA, p)
                  val fb = new File(dirB, p)
                  JarFiles.compareJars(fa, fb, p, { name: String => skipGlobPatterns.exists(matcher.matches(name, _)) }, log, logLimit)
                }
                log.info("Comparison OK.")
              }
            }
          }
        }
      }
      log.info("--== End Performing Comparison  ==--")
    }
  }
}

/**
 * Utility object to compare jar files
 */
object JarFiles {
  // fa and fa are the two jar files that should be compared
  // jarName is some name jar info that will be printed as diagnostic in case of error
  // log is a Logger
  def compareJars(fa: File, fb: File, jarName: String, log: Logger): Unit =
    compareJars(fa, fb, jarName, { _: String => true }, log, 10)

  // additional parameters:
  // within a jar archive, only files whose name satisfy accept will be compared
  // logLimit is a limits on the number of differing elements that will be printed in case of errors
  def compareJars(fa: File, fb: File, jarName: String, accept: String => Boolean, log: Logger, logLimit: Int): Unit = {
    def getEntries(jf: JarFile) = {
      jf.entries.map { je =>
        val name = je.getName()
        if (je.isDirectory() || !accept(name))
          None
        else
          Some(je.getName, je)
      }.flatten.toMap
    }
    val jfa = new JarFile(fa)
    val jfb = new JarFile(fb)
    val aMap = getEntries(jfa)
    val bMap = getEntries(jfb)
    log.debug("Comparing: " + jarName + ", will inspect " + aMap.size + " entries")
    def compareJar(xMap: Map[String, JarEntry], yMap: Map[String, JarEntry], xName: String, yName: String, jar: String) = {
      val xNotY = xMap.keySet.diff(yMap.keySet)
      val ok = xNotY.isEmpty
      if (!ok) {
        log.error("Within two corresponding jar files, some files are in " + xName + " but not in " + yName + ":")
        log.error("jar file: " + jar)
        xNotY.take(logLimit).foreach { s =>
          log.error("  " + s)
        }
        if (xNotY.size > logLimit) log.error("  ... and others")
      }
      ok
    }
    val sameJarAB = compareJar(aMap, bMap, "a", "b", jarName)
    val sameJarBA = compareJar(bMap, aMap, "b", "a", jarName)
    if (!(sameJarAB && sameJarBA)) {
      sys.error("Comparison failed: two corresponding jar files do not contain the same file names")
    }

    // same jar files, and the jar files contain the same file names. Are the files inside the jars
    // actually identical?
    //
    // To begin with, compare CRCs. If no CRCs, resort to comparing the files.
    val files = aMap.keySet
    files.foreach { f =>
      val aCRC = aMap(f).getCrc()
      val bCRC = bMap(f).getCrc()
      if (aCRC != -1 && bCRC != -1) {
        if (aCRC != bCRC) {
          log.error("Within two corresponding jar files, two files have different CRCs.")
          log.error("jar file: " + jarName)
          log.error("file in the jar: " + f)
          def toHex(l: Long) = {
            val h = java.lang.Long.toHexString(l).toUpperCase()
            "0000000000000000".substring(Math.min(16, h.length)) + h
          }
          log.error("a: " + toHex(aCRC) + ", b: " + toHex(bCRC))
          sys.error("Comparison failed: two files in corresponding jar files have different CRCs.")
        }
      } else {
        val ia = jfa.getInputStream(aMap(f))
        val ib = jfb.getInputStream(bMap(f))
        if (!IOUtils.contentEquals(ia, ib)) {
          log.error("Within two corresponding jar files, two files have different contents.")
          log.error("jar file: " + jarName)
          log.error("file in the jar: " + f)
          log.error("The two files in a and in b are not the same.")
          sys.error("Comparison failed: two files in corresponding jar files have different contents.")
        }
      }
    }

  }
}