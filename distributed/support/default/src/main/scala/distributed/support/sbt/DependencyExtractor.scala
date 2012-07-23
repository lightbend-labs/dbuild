package distributed
package support
package sbt

import project.model._
import config.parseStringInto
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process




// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtExtractor {
  
  def extractMetaData(runner: SbtRunner)(projectDir: File, log: logging.Logger): ExtractedBuildMeta = 
        (parseStringInto[ExtractedBuildMeta](runSbtExtractionProject(runner)(projectDir, log)) 
         getOrElse sys.error("Failure to parse build metadata in sbt extractor!"))

  // TODO - Better synchronize?
  private def runSbtExtractionProject(runner: SbtRunner)(project: File, log: logging.Logger): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      log.debug("Extracting SBT build (" + project + ") dependencies into " + result)
      runner.run(
          projectDir = project, 
          log = log,
          javaProps = Map(
              "project.dependency.metadata.file" -> result.getAbsolutePath,
              "remote.project.uri" -> project.getAbsolutePath) ++ runner.localIvyProps
      )("print-deps")
      IO read result
    }
  }
  

}
