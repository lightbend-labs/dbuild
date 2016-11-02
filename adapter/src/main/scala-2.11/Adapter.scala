package sbt.dbuild.hack {
object DbuildHack {
  val Load = sbt.internal.Load
  val applyCross: (String, Option[String => String]) => String =
   sbt.librarymanagement.CrossVersion.applyCross
  val defaultID: (java.io.File,String) => String =
   sbt.internal.BuildDef.defaultID
}
}
package com.typesafe.dbuild.adapter {
import java.io.File

object LoggingInterface {
  val Level = sbt.util.Level
  type Logger = sbt.util.Logger
  type LogEvent = sbt.util.LogEvent
  val ControlEvent = sbt.util.ControlEvent
  val StackTrace = sbt.internal.util.StackTrace
  type BasicLogger = sbt.internal.util.BasicLogger
}

trait StreamLoggerAdapter

object Adapter {
  val IO = sbt.io.IO
  val Path = sbt.io.Path
  type RichFile = sbt.io.RichFile
  type FileFilter = sbt.io.FileFilter
  def toFF = sbt.io.FileFilter.globFilter _
  val DirectoryFilter = sbt.io.DirectoryFilter
  type ExactFilter = sbt.io.ExactFilter
  type NameFilter = sbt.io.NameFilter
  type FileRepository = sbt.librarymanagement.FileRepository
  type Logger = sbt.util.Logger
  def allPaths(f:File) = sbt.io.PathFinder(f).allPaths
  val syntaxio = sbt.io.syntax
  val syntax = sbt.syntax
  type ModuleID = sbt.librarymanagement.ModuleID
  type Artifact = sbt.librarymanagement.Artifact
  type ProjectResolver = sbt.internal.librarymanagement.ProjectResolver
  type ScalaInstance = sbt.internal.inc.ScalaInstance
  val ScalaInstance = sbt.internal.inc.ScalaInstance
  val Load = sbt.dbuild.hack.DbuildHack.Load
  val applyCross = sbt.dbuild.hack.DbuildHack.applyCross
  def defaultID(base: File, prefix: String = "default") =
   sbt.dbuild.hack.DbuildHack.defaultID(base, prefix)

// these bits are inappropriately copied from zinc v1.0.0-X1, where they
// are private now, and exactly from:
// internal/zinc-classpath/src/main/scala/sbt/internal/inc/ScalaInstance.scala
  private val VersionPrefix = "version "
  private def fastActualVersion(scalaLoader: ClassLoader): String =
    {
      val stream = scalaLoader.getResourceAsStream("compiler.properties")
      try {
        val props = new java.util.Properties
        props.load(stream)
        props.getProperty("version.number")
      } finally stream.close()
    }
  import java.net.{ URL, URLClassLoader }
  private def scalaLoader(launcher: xsbti.Launcher): Seq[File] => ClassLoader = jars =>
    new URLClassLoader(jars.map(_.toURI.toURL).toArray[URL], launcher.topLoader)
  private def actualVersion(scalaLoader: ClassLoader)(label: String) =
    try fastActualVersion(scalaLoader)
    catch { case e: Exception => slowActualVersion(scalaLoader)(label) }
  private def slowActualVersion(scalaLoader: ClassLoader)(label: String) =
    {
      val v = try { Class.forName("scala.tools.nsc.Properties", true, scalaLoader).getMethod("versionString").invoke(null).toString }
      catch { case cause: Exception => throw new sbt.internal.inc.InvalidScalaInstance("Scala instance doesn't exist or is invalid: " + label, cause) }
      if (v.startsWith(VersionPrefix)) v.substring(VersionPrefix.length) else v
    }
//
// The code below was deprecated and has been removed from ScalaInstance in zinc 1.0.x,
// however it may work for us.
//
// TODO: use one of the currently supported variants of ScalaInstance.apply()
//
  def scalaInstance(libraryJar: File, compilerJar: File, launcher: xsbti.Launcher, extraJars: File*): ScalaInstance = {
    val classLoader = scalaLoader(launcher)
    val loader = classLoader(libraryJar :: compilerJar :: extraJars.toList)
    val version = actualVersion(loader)(" (library jar  " + libraryJar.getAbsolutePath + ")")
    new ScalaInstance(VersionPrefix, loader, libraryJar, compilerJar, extraJars.toArray, None)
  }
}
}
