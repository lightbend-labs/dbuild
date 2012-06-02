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
  def extract(uri: String): meta.Build = SbtExtractor.extractMetaData(uri)
  def canHandle(system: String): Boolean = "sbt" == system
  
  
}

object SbtExtractor {
  
  def extractMetaData(uri: String): meta.Build = 
    withExtractionProject { dir =>
      (meta.Parser.parseMetaString(runSbtExtractionProject(uri, dir)) 
       getOrElse sys.error("Failure to parse build metadata in sbt extractor!")) 
    }
  
  // TODO - does this even make sense?
  private def withExtractionProject[A](f: File => A): A =
    IO.withTemporaryDirectory { dir =>
      makeTemplateIn(dir)
      f(dir)
    }
  
  
  private def runSbtExtractionProject(uri: String, dir: File): String = {
    val result = dir / "result.sbtmeta"
    // TODO - Better forking process here!
    Process(Seq("sbt", 
        "-Dremote.project.uri=" +uri,
        "-Dproject.dependency.metadata.file="+result.getAbsolutePath,
        "-no-global",
        "print-deps"), Some(dir)).! match {
      case 0 => ()
      case n => sys.error("Failure to run sbt extraction!  Error code: " + n)
    }
    IO read result
  }
  
  /** Creates the template SBT project for extraction... */
  private def makeTemplateIn(dir: File): Unit = {
    val projectDir = dir / "project"
    projectDir.mkdirs
    val in = (Option(getClass.getClassLoader.getResourceAsStream("SbtExtractionBuild.scala")) 
        getOrElse sys.error("Could not find SbtExtractionBuild.scala on the path."))
    try IO.transfer(in, projectDir / "build.scala")
    finally in.close
  }
}