import sbt._
import SyntaxAdapter.syntax._

object Dependencies extends CommonDependencies {

  val akkaActor      = "com.typesafe.akka" %% "akka-actor" % "2.1.4"

// 2.1.1 per 2.10, 1.12.4.1 per 2.9.x. 1.12.3 is the only common one between 2.9 and 2.10
  val specs2         = "org.specs2" %% "specs2" % "2.1.1" % "it,test"

  def sbtIo(v:String)        = "org.scala-sbt" % "io" % v
  def sbtIvy(v:String)       = "org.scala-sbt" % "ivy" % v
  def sbtLogging(v:String)   = "org.scala-sbt" % "logging" % v
  def sbtLaunchInt(v:String) = "org.scala-sbt" % "launcher" % v
  def sbtLauncher(v:String)  = "org.scala-sbt" % "launcher" % v
  def sbtSbt(v:String)       = "org.scala-sbt" % "sbt" % v

  val zincProvidedIf211      = Seq[ModuleID]()
  val gpgLibIf210            = Seq("com.jsuereth" %% "gpg-library" % "0.8.2")
}
