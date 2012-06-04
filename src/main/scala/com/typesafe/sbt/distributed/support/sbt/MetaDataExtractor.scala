package com.typesafe.sbt.distributed
package support
package sbt

import _root_.sbt.{IO, Path}
import Path._
import java.io.File
import sys.process.Process

/** A helper that knows how to extract SBT build
 * dependencies.
 */
class SbtExtractor extends meta.Extractor {
  override def extract(config: meta.BuildConfig): meta.ExtractedBuildMeta =
    SbtExtractor.extractMetaData(config.uri, config.directory)
  def canHandle(system: String): Boolean = "sbt" == system
}

object SbtExtractor {
  
  def extractMetaData(
      uri: String, 
      projDirectory: String, 
      base: File = new File(".extraction")): meta.ExtractedBuildMeta = 
        (meta.ExtractedMetaParser.parseMetaString(runSbtExtractionProject(uri, projDirectory, base)) 
         getOrElse sys.error("Failure to parse build metadata in sbt extractor!")) 
  
  // TODO - does this even make sense?
  private def withExtractionProject[A](base: File)(f: File => A): A =
    IO.withTemporaryDirectory { dir =>
      f(dir)
    }
  
  private def runSbtExtractionProject(uri: String, projDirectory: String, base: File): String = synchronized {
    IO.withTemporaryFile("result", "sbtmeta") { result =>
      // TODO - Do we need a temporary directory for this, or can we re-use
      // the same one and stay synchronized?
      val globalBase = base / "sbtbase"      
      makeTemplateIn(base)    
    // TODO - Better forking process here!
      Process(Seq("sbt", 
        "-Dremote.project.uri=" +uri,
        "-Dremote.project.nesteddir="+projDirectory,
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