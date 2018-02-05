import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.module.descriptor.ModuleDescriptor

package sbt.dbuild.hack {
object DbuildHack {
  val Load = sbt.Load
  val ExceptionCategory = sbt.ExceptionCategory
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
  def newIvyPaths(baseDirectory: java.io.File, ivyHome: Option[java.io.File]) =
    new sbt.IvyPaths(baseDirectory, ivyHome)
  type FileFilter = sbt.FileFilter
  def toFF = sbt.FileFilter.globFilter _
  val DirectoryFilter = sbt.DirectoryFilter
  type ExactFilter = sbt.ExactFilter
  type NameFilter = sbt.NameFilter
  type FileRepository = sbt.FileRepository
  type Logger = sbt.Logger
  import Path._
  def allPaths(f:File) = sbt.PathFinder(f).***
  val syntaxio = new {}
  type ModuleID = sbt.ModuleID
  type Artifact = sbt.Artifact
  type CrossVersion = sbt.CrossVersion
  type IvyScala = sbt.IvyScala
  val keyIvyScala = sbt.Keys.ivyScala
  def interProjectResolver(k:Map[ModuleRevisionId, ModuleDescriptor]) = 
    new sbt.RawRepository(new sbt.ProjectResolver("inter-project", k))
  def moduleWithName(m:ModuleID, n:String) = m.copy(name=n)
  def moduleWithRevision(m:ModuleID, r:String) = m.copy(revision=r)
  def moduleWithCrossVersion(m:ModuleID, cross:CrossVersion) = m.copy(crossVersion=cross)
  def moduleWithExplicitArtifacts(m:ModuleID, ea:Seq[Artifact]) = m.copy(explicitArtifacts=ea)
  def moduleWithExtraAttributes(m:ModuleID, ea:Map[String,String]) = m.copy(extraAttributes=ea)
  def ivyScalaWithCheckExplicit(i:IvyScala, ce:Boolean) = i.copy(checkExplicit=ce)
  def artifactWithClassifier(a:Artifact, cl:Option[String]) = a.copy(classifier=cl)
  val crossDisabled = sbt.CrossVersion.Disabled
  type crossDisabled = sbt.CrossVersion.Disabled.type
  val crossBinary = sbt.CrossVersion.binary
  type crossBinary = sbt.CrossVersion.Binary
  val crossFull = sbt.CrossVersion.full
  type crossFull = sbt.CrossVersion.Full
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
