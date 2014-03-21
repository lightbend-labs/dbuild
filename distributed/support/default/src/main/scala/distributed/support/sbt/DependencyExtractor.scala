package distributed
package support
package sbt

import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import distributed.project.model.Utils.readValue
import distributed.logging.Logger.logFullStackTrace


// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtExtractor {
  
  def extractMetaData(runner: SbtRunner)(projectDir: File, extra: SbtExtraConfig, log: logging.Logger, debug: Boolean): ExtractedBuildMeta =
    try readValue[ExtractedBuildMeta](runSbtExtractionProject(runner)(projectDir, extra, log, debug)) 
    catch { case e:Exception =>
      logFullStackTrace(log, e)
      sys.error("Failure to parse build metadata in sbt extractor!")
    }

  // TODO - Better synchronize?
  private def runSbtExtractionProject(runner: SbtRunner)(projectDir: File, extra: SbtExtraConfig, log: logging.Logger, debug: Boolean): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      log.debug("Extracting SBT build (" + projectDir + ") dependencies into " + result)
      val scalaCompiler = extra.extractionVersion getOrElse
        sys.error("Internal error: \"compiler\" has not been expanded. Please report.")
      val setScalaCommand: Seq[String] = scalaCompiler match {
        case "standard" =>
          log.info("Using project's standard Scala version during extraction.")
          Seq.empty
        case v =>
          log.info("Using Scala " + v + " during extraction.")
          Seq("set every scalaVersion := \"" + v + "\"")
      }
      
      // We need to set up all the plugins, and other changes to the build definitions that are necessary
      // in order for dbuild to operate.
      //
      // For extraction, we need to find out the number of levels "n" and place:
      // - in levels 0..n-1, silence Ivy (if requested)
      // - in levels 0..n-1, add an onLoad() that performs the actual dependency extraction for that level
      // - in levels 1..n, add a suitable addSbtPlugin()
      // - create in levels 0..n-1 a directory ".dbuild" in order to house the extraction output
      // where each level is the main dir, followed by "/project" n times.
      //
      // We know that projectDir exists, and that it contains no extranoues files (as per the resolve() contract)
      // So:
      val levels = SbtRunner.buildLevels(projectDir)
      log.debug("This sbt build has definitions on "+levels+" levels.")
      // create the .dbuild dirs
      SbtRunner.prepDBuildDirs(projectDir, levels)
      //
      // We need a suitable .sbt file in each directory. Some definitions go only in the first one,
      // some in all the middle ones, and some only in the last one.
      // Create a tuple for (first, middle, last) possible contents 
      def generateSbtFiles(allButLast: String, allButFirst: String): (String, String, String) =
        (allButLast, allButLast + allButFirst, allButFirst)

      val allButLast = SbtRunner.ivyQuiet(debug)+SbtRunner.onLoad("""
          println("I am happy to report that I can transform the state in this dir")
          com.typesafe.dbuild.DependencyAnalysis.callMe(state, "4", "we", "quy")
          """)
      val allButFirst = SbtRunner.addDBuildPlugin

      val (first, middle, last) = generateSbtFiles(allButLast, allButFirst)
      // this is the sequence of contents of the various files 
      val sbtFiles = first +: Stream.fill(levels - 1)(middle) :+ last
      // Let's place them in the required dirs
      SbtRunner.writeSbtFiles(projectDir, sbtFiles, log, debug)

      runner.run(
        projectDir = projectDir,
        sbtVersion = extra.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
        log = log,
        javaProps = Map(
          "dbuild.project.dependency.metadata.file" -> result.getAbsolutePath,
          "dbuild.project.dependency.metadata.subprojects" -> extra.projects.mkString(","),
          "dbuild.project.dependency.metadata.excluded" -> extra.exclude.mkString(","),
          "dbuild.project.dependency.metadata.debug" -> debug.toString,
          "dbuild.remote.project.uri" -> projectDir.getAbsolutePath), // ++ runner.localIvyProps
//        extraArgs = extra.options)((extra.commands ++ setScalaCommand).:+("print-deps"): _*)
        extraArgs = extra.options)((extra.commands ++ setScalaCommand).:+(""): _*)

        Thread.sleep(2000)
        sys.exit(0)

        IO read result
    }
  }
  

}
