import sbt._

object Dependencies extends CommonDependencies {

  val akkaActor      = "com.typesafe.akka" %% "akka-actor" % "2.1.4"

  val specs2         = "org.specs2" %% "specs2" % "2.1.1" % "it,test"

  val dispatch       = "net.databinder.dispatch" %% "dispatch-core" % "0.11.3"

  def sbtIo(v:String)        = "org.scala-sbt" % "io" % v
  def sbtIvy(v:String)       = "org.scala-sbt" % "ivy" % v
  def sbtLogging(v:String)   = "org.scala-sbt" % "logging" % v
  // sbt 0.13.15 uses launcher 1.0.1
  def sbtLaunchInt(v:String) = "org.scala-sbt" % "launcher-interface" % "1.0.1"
  def sbtSbt(v:String)       = "org.scala-sbt" % "sbt" % v

  val launcher               = "org.scala-sbt" % "launcher" % "1.0.2-dbuild7" % "provided"

  val zincIf212              = None:Option[String=>ModuleID]
  val gpgLibIf210            = Seq("com.jsuereth" %% "gpg-library" % "0.8.2")
}
