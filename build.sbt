import Dependencies._

def MyVersion: String = "0.9.6-SNAPSHOT"

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
    licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0"))
/*
,
    bintrayReleaseOnPublish := false,
    bintrayOrganization := Some("typesafe"),
    bintrayRepository := "ivy-releases",
    bintrayPackage := "dbuild"
*/
  )
)

import RemoteDepHelper._

def skip210 =
  Seq(skip in compile <<= scalaVersion.map(v => v.startsWith("2.10") || v.startsWith("2.11")),
      sources in doc in Compile <<= (sources in doc in Compile,skip in compile).map( (c,s) =>
        if(s) List() else c ) )

def selectScalaVersion =
  scalaVersion <<= (sbtVersion in sbtPlugin).apply( sb => if (sb.startsWith("0.12")) "2.9.2" else if (sb.startsWith("0.13"))
     "2.10.6" else "2.11.8" )


SbtSupport.buildSettings


// This subproject only has dynamically
// generated source files, used to adapt
// the source file to sbt 0.12/0.13/1.0
lazy val adapter = (
  SubProj("adapter")
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
  settings(sourceGenerators in Compile += task {
    val dir = (sourceManaged in Compile).value
    val fileName = "Adapter.scala"
    val file = dir / fileName
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
    IO.write(file, (
if (v.startsWith("1.0")) """
package com.typesafe.dbuild.adapter

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
  val DirectoryFilter = sbt.io.DirectoryFilter
  type Logger = sbt.util.Logger
  def allPaths(f:java.io.File) = sbt.io.PathFinder(f).allPaths
  val syntaxio = sbt.io.syntax
}
""" else """
package com.typesafe.dbuild.adapter

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
  val DirectoryFilter = sbt.DirectoryFilter
  type Logger = sbt.Logger
  import Path._
  def allPaths(f:java.io.File) = sbt.PathFinder(f).***
  val syntaxio = new {}
}
""")+("""
object Defaults {
  val version = "%s"
  val org = "%s"
  val hash = "%s"
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
  settings(skip210:_*)
)

lazy val deploy = (
  SubProj("deploy")
  dependsOn(adapter)
  dependsOnRemote(jackson, typesafeConfig, commonsLang, aws, uriutil, dispatch, commonsIO, jsch)
  settings(libraryDependencies += jacks(scalaVersion.value))
  dependsOnSbt(sbtLogging, sbtIo)
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
  settings(skip210:_*)
)



/*
  settings(sourceGenerators in Compile += task {
    val dir = (sourceManaged in Compile).value
    val fileName = "Update.scala"
    val file = dir / fileName
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
    val where = if (v.startsWith("0.12")) "Project" else "Def"
    IO.write(file, """
package com.typesafe.dbuild.plugin
object SbtUpdate {
def update[T]: (sbt.%s.ScopedKey[T]) => (T => T) => sbt.%s.Setting[T] = sbt.%s.update[T]
}
""" format (where, where, where))
    Seq(file) }
  )
*/

