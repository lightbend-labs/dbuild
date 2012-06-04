package com.typesafe.sbt.distributed
package support
package sbt

import _root_.sbt.{IO, Path, PathExtra}
import Path._
import java.io.File
import sys.process.Process

/** A helper that knows how to extract SBT build
 * dependencies.
 */
class SbtDependencyExtractor(base: File = new File(".sbtextraction")) extends meta.BuildDependencyExtractor {
  override def extract(config: meta.BuildConfig, dir: java.io.File): meta.ExtractedBuildMeta =
    if(config.directory.isEmpty) SbtExtractor.extractMetaData(dir, base)
    else SbtExtractor.extractMetaData(new File(dir, config.directory), base)
  def canHandle(system: String): Boolean = "sbt" == system
}


// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtExtractor {
  
  def extractMetaData(projectDir: File, base: File): meta.ExtractedBuildMeta = 
        (meta.ExtractedMetaParser.parseMetaString(runSbtExtractionProject(projectDir, base)) 
         getOrElse sys.error("Failure to parse build metadata in sbt extractor!"))

  // TODO - Beter synchronize!
  private def runSbtExtractionProject(project: File, base: File): String = {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      // TODO - Do we need a temporary directory for this, or can we re-use
      // the same one and stay synchronized?
      val globalBase = base / "sbtbase"      
      makeTemplateIn(base)    
    // TODO - Better forking process here!
      Process(Seq("sbt", 
        "-Dremote.project.uri=file://" +project.getAbsolutePath(),
        "-Dproject.dependency.metadata.file="+result.getAbsolutePath,
        "-Dsbt.global.base="+globalBase.getAbsolutePath,
        //"-no-global",
        "-Dsbt.log.noformat=true",
        "print-deps"), Some(base)).! match {
          case 0 => ()
          case n => sys.error("Failure to run sbt extraction!  Error code: " + n)
        }
      IO read result
    }
  }
  
  /** Creates the template SBT project for extraction... */
  private def makeTemplateIn(dir: File): Unit = 
    if(!(dir / "project" / "build.scala").exists) {
      val projectDir = dir / "project"
      projectDir.mkdirs
      val in = (Option(getClass.getClassLoader.getResourceAsStream("SbtExtractionBuild.scala")) 
          getOrElse sys.error("Could not find SbtExtractionBuild.scala on the path."))
      try IO.transfer(in, projectDir / "build.scala")
      finally in.close
    }
}