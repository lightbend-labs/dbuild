import sbt._


object Dependencies {

  val akkaVersion = "2.0.1"
  val sbtVersion = "0.12.0-RC1"

  val typesafeConfig = "com.typesafe" % "config" % "0.4.1"
  val akkaActor      = "com.typesafe.akka" % "akka-actor" % akkaVersion

  val specs2         = "org.specs2" %% "specs2" % "1.10" % "test"

  val sbtIo          = "org.scala-sbt" % "io" % sbtVersion
  val sbtLogging     = "org.scala-sbt" % "logging" % sbtVersion

}
