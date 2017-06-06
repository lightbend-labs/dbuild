import sbt._

object Dependencies extends CommonDependencies {

  val akkaActor      = "com.typesafe.akka" %% "akka-actor" % "2.4.17"

  val specs2         = "org.specs2" %% "specs2-core" % "3.8.8" % "it,test"

  val dispatch       = "net.databinder.dispatch" %% "dispatch-core" % "0.12.1"

// Once new versions of sbt/launcher/libraryManagement/zinc etc are released, move to those versions
  def sbtIo(v:String)             = "org.scala-sbt" %% "io" % "1.0.0-M11"
  def sbtIvy(v:String)            = "org.scala-sbt" %% "librarymanagement" % "1.0.0-X10"
  def sbtLogging(v:String)        = "org.scala-sbt" %% "util-logging" % "1.0.0-M23"
  // sbt 1.0.0-M5 uses launcher 1.0.0
  def sbtLaunchInt(v:String)      = "org.scala-sbt" % "launcher-interface" % "1.0.0"

  val launcher                    = "org.scala-sbt" % "launcher" % "1.0.2-dbuild7" % "provided"

  def sbtSbt(v:String)            = "org.scala-sbt" % "sbt" % v
  val zincIf212                   = Some({ v:String => "org.scala-sbt" %% "zinc" % "1.0.0-X14" })

  val gpgLibIf210                 = Seq[librarymanagement.ModuleID]()
}
