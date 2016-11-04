package sbt.dbuild.hack {
object DbuildHack {
  val Load = sbt.Load
}
}

package com.typesafe.dbuild.adapter {
import java.io.File

object LoggingInterface {
  val Level = sbt.Level
  type Logger = sbt.Logger
  type LogEvent = sbt.LogEvent
  val ControlEvent = sbt.ControlEvent
  val StackTrace = sbt.StackTrace
  type BasicLogger = sbt.BasicLogger
}

import LoggingInterface.Level._
trait StreamLoggerAdapter {
  def log(level: Value, message: => String): Unit
  def log(label: String, message: String): Unit
  def err(s: => String): Unit = log(Error, s)
  def out(s: => String): Unit = log(Info.toString, s)
}

object Adapter {
  val IO = sbt.IO
  val Path = sbt.Path
  type RichFile = sbt.RichFile
  type FileFilter = sbt.FileFilter
  def toFF = sbt.FileFilter.globFilter _
  val DirectoryFilter = sbt.DirectoryFilter
  type ExactFilter = sbt.ExactFilter
  type NameFilter = sbt.NameFilter
  type FileRepository = sbt.FileRepository
  type Logger = sbt.Logger
  import Path._
  def allPaths(f:File) = sbt.PathFinder(f).***
  val syntax = new {}
  val syntaxio = syntax
  type ModuleID = sbt.ModuleID
  type Artifact = sbt.Artifact
  type ProjectResolver = sbt.ProjectResolver
  type ScalaInstance = sbt.ScalaInstance
  lazy val ScalaInstance = sbt.ScalaInstance
  lazy val Load = sbt.dbuild.hack.DbuildHack.Load
  val applyCross: (String, Option[String => String]) => String =
   sbt.CrossVersion.applyCross
  def defaultID(base: File, prefix: String = "default") =
   sbt.Build.defaultID(base, prefix)
  def scalaInstance(libraryJar: File, compilerJar: File, launcher: xsbti.Launcher, extraJars: File*): ScalaInstance =
    ScalaInstance(libraryJar, compilerJar, launcher, extraJars:_*)
}
}
