package distributed
package support
package sbt

import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import distributed.project.model.Utils.readValue


// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtExtractor {
  
  def extractMetaData(runner: SbtRunner)(projectDir: File, extra: SbtExtraConfig, log: logging.Logger): ExtractedBuildMeta =
    try readValue[ExtractedBuildMeta](runSbtExtractionProject(runner)(projectDir, extra, log)) 
    catch { case e:Exception =>
      e.printStackTrace
      sys.error("Failure to parse build metadata in sbt extractor!")
    }

  // TODO - Better synchronize?
  private def runSbtExtractionProject(runner: SbtRunner)(project: File, extra: SbtExtraConfig, log: logging.Logger): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      log.debug("Extracting SBT build (" + project + ") dependencies into " + result)
      runner.run(
          projectDir = project, 
          log = log,
          javaProps = Map(
              "project.dependency.metadata.file" -> result.getAbsolutePath,
              "remote.project.uri" -> project.getAbsolutePath), // ++ runner.localIvyProps
          extraArgs = extra.options
      )(extra.commands.:+("print-deps"):_*)
      IO read result
    }
  }
  

}
