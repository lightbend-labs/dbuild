package distributed
package support
package sbt

import project.dependencies.{BuildDependencyExtractor, ExtractedDependencyFileParser}
import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process

/** A helper that knows how to extract SBT build
 * dependencies.
 */
class SbtDependencyExtractor(base: File = new File(".sbtextraction")) extends BuildDependencyExtractor {
  override def extract(config: BuildConfig, dir: File): ExtractedBuildMeta =
    if(config.directory.isEmpty) SbtExtractor.extractMetaData(dir, base)
    else SbtExtractor.extractMetaData(new File(dir, config.directory), base)
  def canHandle(system: String): Boolean = "sbt" == system
}


// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtExtractor {
  
  def extractMetaData(projectDir: File, base: File): ExtractedBuildMeta = 
        (ExtractedDependencyFileParser.parseMetaString(runSbtExtractionProject(projectDir, base)) 
         getOrElse sys.error("Failure to parse build metadata in sbt extractor!"))

  // TODO - Beter synchronize!
  private def runSbtExtractionProject(project: File, base: File): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      // TODO - Do we need a temporary directory for this, or can we re-use
      // the same one and stay synchronized?
      val globalBase = base / "sbtbase"      
      makeGlobalBaseIn(globalBase)    
    // TODO - Better forking process here!
      Process(Seq("sbt", 
        "-Dremote.project.uri=file://" +project.getAbsolutePath(),
        "-Dproject.dependency.metadata.file="+result.getAbsolutePath,
        "-Dsbt.global.base="+globalBase.getAbsolutePath,
        //"-no-global",
        //"-Dsbt.version=0.12.0-RC1",
        "-sbt-version",
        "0.12.0-RC1",
        "-Dsbt.log.noformat=true",
        "print-deps"), Some(project)).! match {
          case 0 => ()
          case n => sys.error("Failure to run sbt extraction!  Error code: " + n)
        }
      IO read result
    }
  }
  
  /** Creates the template SBT project for extraction... */
  private def makeGlobalBaseIn(dir: File): Unit = 
    if(!(dir / "plugins" / "deps.sbt").exists) {
      val pluginDir = dir / "plugins"
      pluginDir.mkdirs
      transferResource("sbt/deps.sbt", pluginDir / "deps.sbt")
    }
  
  private def transferResource(r: String, f: File): Unit = {
     val in = (Option(getClass.getClassLoader.getResourceAsStream(r)) 
          getOrElse sys.error("Could not find "+r+" on the path."))
     try IO.transfer(in, f)
     finally in.close
  }
}
