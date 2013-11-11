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
  
  def extractMetaData(runner: SbtRunner)(projectDir: File, extra: SbtExtraConfig, log: logging.Logger): ExtractedBuildMeta =
    try readValue[ExtractedBuildMeta](runSbtExtractionProject(runner)(projectDir, extra, log)) 
    catch { case e:Exception =>
      logFullStackTrace(log, e)
      sys.error("Failure to parse build metadata in sbt extractor!")
    }

  // TODO - Better synchronize?
  private def runSbtExtractionProject(runner: SbtRunner)(project: File, extra: SbtExtraConfig, log: logging.Logger): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      log.debug("Extracting SBT build (" + project + ") dependencies into " + result)
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
      runner.run(
        projectDir = project,
        sbtVersion = extra.sbtVersion getOrElse sys.error("Internal error: sbtVersion has not been expanded. Please report."),
        log = log,
        javaProps = Map(
          "dbuild.project.dependency.metadata.file" -> result.getAbsolutePath,
          "dbuild.project.dependency.metadata.subprojects" -> extra.projects.mkString(","),
          "dbuild.project.dependency.metadata.excluded" -> extra.exclude.mkString(","),
          "dbuild.remote.project.uri" -> project.getAbsolutePath), // ++ runner.localIvyProps
        extraArgs = extra.options)((extra.commands ++ setScalaCommand).:+("print-deps"): _*)
      IO read result
    }
  }
  

}
