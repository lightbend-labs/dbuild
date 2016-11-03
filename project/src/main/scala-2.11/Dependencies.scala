import sbt._
import SyntaxAdapter.syntax._

object Dependencies extends CommonDependencies {

  val akkaActor      = "com.typesafe.akka" %% "akka-actor" % "2.4.11"

// 2.1.1 per 2.10, 1.12.4.1 per 2.9.x. 1.12.3 is the only common one between 2.9 and 2.10
  val specs2         = "org.specs2" %% "specs2" % "3.7" % "it,test"

  def sbtIo(v:String)        = "org.scala-sbt" %% "io" % "1.0.0-M6"
  def sbtIvy(v:String)       = "org.scala-sbt" %% "librarymanagement" % "0.1.0-M12"
  def sbtLogging(v:String)   = "org.scala-sbt" %% "util-logging" % "0.1.0-M14"
  def sbtLaunchInt(v:String) = "org.scala-sbt" % "launcher" % "1.0.0"
  def sbtLauncher(v:String)  = "org.scala-sbt" % "launcher" % "1.0.0"
  def sbtSbt(v:String)       = "org.scala-sbt" % "sbt" % v

  val zincIf211              = Seq("org.scala-sbt" %% "zinc" % "1.0.0-X1")
  val gpgLibIf210            = Seq[librarymanagement.ModuleID]()
}
