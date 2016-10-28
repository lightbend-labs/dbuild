import Dependencies._

def MyVersion: String = "0.9.7-SNAPSHOT"

def SubProj(name: String) = (
  Project(name, file(if (name=="root") "." else name))
  configs( IntegrationTest )
  settings( Defaults.itSettings : _*)
  settings(
    version := MyVersion,
    organization := "com.typesafe.dbuild",
    selectScalaVersion,
    libraryDependencies += specs2(scalaVersion.value),
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    publishMavenStyle := false,
    licenses += ("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0")),
    licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0")) /*,
    bintrayReleaseOnPublish := false,
    bintrayOrganization := Some("typesafe"),
    bintrayRepository := "ivy-releases",
    bintrayPackage := "dbuild" */
  )
)

import RemoteDepHelper._

def skip211 = Seq(
      skip in compile := scalaVersion.value.startsWith("2.11"),
      sources in doc in Compile :=
        { if((skip in compile).value) List() else (sources in doc in Compile).value }
     )

def selectScalaVersion =
  scalaVersion := {
    val sb = (sbtVersion in sbtPlugin).value
    if (sb.startsWith("0.13")) "2.10.6" else "2.11.8"
  }

lazy val root = (
  SubProj("root")
  aggregate(graph, hashing, logging, actorLogging, proj, actorProj, deploy,
            core, plugin, build, support, supportGit, repo, metadata, docs, dist, indexmeta)
  settings(publish := (), publishLocal := (), version := MyVersion)
  //settings(CrossPlugin.crossBuildingSettings:_*)
  //settings(CrossBuilding.crossSbtVersions := Seq("0.13","1.0.0-M4"), selectScalaVersion)
  settings(commands += Command.command("release") { state =>
    "clean" :: "publish" :: state
  })
)

// This subproject only has dynamically
// generated source files, used to adapt
// the source file to sbt 0.13/1.0
lazy val adapter = (
  SubProj("adapter")
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt, sbtIvy, sbtSbt)
  dependsIf211(zincIf211)
  settings(sourceGenerators in Compile += task {
    val dir = (sourceManaged in Compile).value
    val fileName = "Adapter.scala"
    val file = dir / fileName
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
    IO.write(file, (
if (v.startsWith("1.0")) """
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
""" else """
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
  val ScalaInstance = sbt.ScalaInstance
  val Load = sbt.dbuild.hack.DbuildHack.Load
  val applyCross: (String, Option[String => String]) => String =
   sbt.CrossVersion.applyCross
  def defaultID(base: File, prefix: String = "default") =
   sbt.Build.defaultID(base, prefix)
  def scalaInstance(libraryJar: File, compilerJar: File, launcher: xsbti.Launcher, extraJars: File*): ScalaInstance =
    ScalaInstance(libraryJar, compilerJar, launcher, extraJars:_*)
}
""")+("""
object Defaults {
  val version = "%s"
  val org = "%s"
  val hash = "%s"
}
}""" format (version.value, organization.value, scala.sys.process.Process("git log --pretty=format:%H -n 1").lines.head))

)
    Seq(file)
  })
)

lazy val graph = (
  SubProj("graph")
)

lazy val hashing = (
  SubProj("hashing")
  dependsOnRemote(typesafeConfig)
)

lazy val indexmeta = (
  SubProj("indexmeta")
)

lazy val logging = (
  SubProj("logging")
  dependsOn(adapter,graph)
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
)

lazy val actorLogging = (
  SubProj("actorLogging")
  dependsOn(logging)
  dependsOnAkka()
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
  settings(skip211:_*)
)

lazy val metadata = (
  SubProj("metadata")
  dependsOn(graph, hashing, indexmeta, deploy)
  dependsOnRemote(jackson, typesafeConfig, /*sbtCollections,*/ commonsLang)
  settings(libraryDependencies += jacks(scalaVersion.value))
)

lazy val repo = (
  SubProj("repo")
  dependsOn(adapter, metadata, logging)
  dependsOnRemote(mvnAether, dispatch, aether, aetherApi, aetherSpi, aetherUtil, aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, aetherWagon, mvnAether)
  dependsOnSbt(sbtIo, sbtLaunchInt)
)

lazy val core = (
  SubProj("core")
  dependsOnRemote(javaMail)
  dependsOn(adapter,metadata, graph, hashing, logging, repo)
  dependsOnSbt(sbtIo)
)

lazy val proj = (
  SubProj("proj")
  dependsOn(core, repo, logging)
  dependsOnRemote(javaMail, commonsIO)
  dependsOnSbt(sbtIo, sbtIvy)
)

lazy val actorProj = (
  SubProj("actorProj")
  dependsOn(core, actorLogging, proj)
  dependsOnSbt(sbtIo, sbtIvy)
  settings(skip211:_*)
)

lazy val support = (
  SubProj("support")
  dependsOn(core, repo, metadata, proj % "compile->compile;it->compile", logging % "it")
  dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, aether, aetherApi, aetherSpi, aetherUtil,
                  aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, slf4jSimple)
  dependsOnSbt(sbtLaunchInt, sbtIvy)
  settings(SbtSupport.buildSettings:_*)
  settings(SbtSupport.settings:_*)
  settings(
    // We hook the testLoader of it to make sure all the it tasks have a legit sbt plugin to use.
    // Technically, this just pushes every project.  We could outline just the plugin itself, but for now
    // we don't care that much.
    testLoader in IntegrationTest := {
      val ignore = publishLocal.all(ScopeFilter(inAggregates(LocalRootProject, includeRoot=false))).value
      (testLoader in IntegrationTest).value
    },
    parallelExecution in IntegrationTest := false
  )
) 

// A separate support project for git/jgit
lazy val supportGit = (
  SubProj("supportGit") 
  dependsOn(core, repo, metadata, proj, support)
  dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, jgit)
  dependsOnSbt(sbtLaunchInt, sbtIvy)
  settings(SbtSupport.buildSettings:_*)
  settings(SbtSupport.settings:_*)
  settings(skip211:_*)
)

// SBT plugin
lazy val plugin = (
  SubProj("plugin") 
  settings(sbtPlugin := true)
  dependsOn(adapter, support, metadata)
  dependsOnSbt(sbtIo)
)

lazy val dist = (
  SubProj("dist")
  settings(Packaging.settings(build,repo):_*)
)

lazy val deploy = (
  SubProj("deploy")
  dependsOn(adapter)
  dependsOnRemote(jackson, typesafeConfig, commonsLang, aws, uriutil, dispatch, commonsIO, jsch)
  settings(libraryDependencies += jacks(scalaVersion.value))
  dependsOnSbt(sbtLogging, sbtIo)
)

lazy val build = (
  SubProj("build")
  dependsOn(actorProj, support, supportGit, repo, metadata, deploy, proj)
  dependsOnRemote(aws, uriutil, dispatch, jsch, oro, scallop, commonsLang)
  dependsIf210(gpgLibIf210) // not available on 2.11 at the moment
  dependsOnSbt(sbtLaunchInt, sbtLauncher)
  settings(skip211:_*)
  settings(SbtSupport.settings:_*)
  settings(
    // We hook the testLoader of it to make sure all the it tasks have a legit sbt plugin to use.
    // Technically, this just pushes every project.  We could outline just the plugin itself, but for now
    // we don't care that much.
    testLoader in IntegrationTest := {
      val ignore = publishLocal.all(ScopeFilter(inAggregates(LocalRootProject, includeRoot=false))).value
      (testLoader in IntegrationTest).value
    },
    parallelExecution in IntegrationTest := false
  )
)

lazy val docs = (
  SubProj("docs")
  settings(DocsSupport.settings:_*)
)

