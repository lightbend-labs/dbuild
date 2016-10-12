import Dependencies._

def MyVersion: String = "0.9.6-SNAPSHOT"

def Proj(name: String) = (
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

lazy val graph = (
  Proj("graph")
)

lazy val hashing = (
  Proj("hashing")
  dependsOnRemote(typesafeConfig)
)

lazy val indexmeta = (
  Proj("indexmeta")
)

lazy val logging = (
  Proj("logging")
  dependsOn(graph)
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
  settings(sourceGenerators in Compile += task {
    val dir = (sourceManaged in Compile).value
    val fileName = "LoggingInterface.scala"
    val file = dir / fileName
//    val s = streams.value
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
//    s.log.info("Generating \"" + fileName + "\" for sbt " + v + " and Scala " + sv)
    val (where1,where2) = if (v.startsWith("1.0")) ("sbt.util","sbt.internal.util") else ("sbt","sbt")
    IO.write(file, ("""
package com.typesafe.dbuild.logging
object LoggingInterface {
  val Level = %s.Level
  type Logger = %s.Logger
  type LogEvent = %s.LogEvent
  val ControlEvent = %s.ControlEvent
  val StackTrace = %s.StackTrace
  type BasicLogger = %s.BasicLogger
}
import LoggingInterface.Level._
final class StreamLogger(out: java.io.PrintStream, debug: Boolean) extends StreamLoggerBase(out, debug) {
""" + (if (!v.startsWith("1.0")) """
  def err(s: => String): Unit = log(Error, s)
  def out(s: => String): Unit = log(Info.toString, s)
}""" else "}")) format (where1, where1, where1, where1, where2, where2))
    Seq(file) }
  )
)

lazy val actorLogging = (
  Proj("actorLogging")
  dependsOn(logging)
  dependsOnAkka()
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
  settings(skip210:_*)
)

/*
  settings(sourceGenerators in Compile := (sourceGenerators in Compile).value :+ {
    val dir = (sourceManaged in Compile).value
    val fileName = "Update.scala"
    val file = dir / fileName
    val s = streams.value
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
    s.log.info("Generating \"" + fileName + "\" for sbt " + v + " and Scala " + sv)
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
