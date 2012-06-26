package distributed
package support
package sbt

import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import SbtHelper._




// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtExtractor {
  
  def extractMetaData(projectDir: File, base: File, log: logging.Logger): ExtractedBuildMeta = 
        (ExtractedBuildMetaParser.parseMetaString(runSbtExtractionProject(projectDir, base, log)) 
         getOrElse sys.error("Failure to parse build metadata in sbt extractor!"))

  // TODO - Beter synchronize!
  private def runSbtExtractionProject(project: File, base: File, log: logging.Logger): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      // TODO - Do we need a temporary directory for this, or can we re-use
      // the same one and stay synchronized?
      val globalBase = base / "sbtbase"      
      makeGlobalBaseIn(globalBase)    
      // TODO - Figure out logging!
      // TODO - Figure out dependencies?
      /*
      val runner = new launcher.MySbtRunner(globalBase)
      System.setProperty("project.dependency.metadata.file", result.getAbsolutePath)
      System.setProperty("sbt.global.base", globalBase.getAbsolutePath)
      runner.runCommand(project, "print-deps")
      */
      Process(Seq("sbt", 
        "-Dremote.project.uri=file://" +project.getAbsolutePath(),
        "-Dproject.dependency.metadata.file="+result.getAbsolutePath,
        "-Dsbt.global.base="+globalBase.getAbsolutePath,
        "-sbt-version",
        SbtConfig.sbtVersion,
        "-Dsbt.log.noformat=true",
        "print-deps"), Some(project)) ! log match {
          case 0 => ()
          case n => sys.error("Failure to run sbt extraction!  Error code: " + n)
        }
      IO read result
    }
  }
  

}
