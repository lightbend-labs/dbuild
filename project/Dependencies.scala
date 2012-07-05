import sbt._


object Dependencies {

  val akkaVersion = "2.0.1"
  val sbtVersion = "0.12.0-RC3"

  val typesafeConfig = "com.typesafe" % "config" % "0.4.1"
  val akkaActor      = "com.typesafe.akka" % "akka-actor" % akkaVersion

  val specs2         = "org.specs2" %% "specs2" % "1.10" % "test"

  val sbtIo          = "org.scala-sbt" % "io" % sbtVersion
  val sbtLogging     = "org.scala-sbt" % "logging" % sbtVersion
  val sbtCollections     = "org.scala-sbt" % "collections" % sbtVersion
  val sbtLaunchInt   = "org.scala-sbt" % "launcher-interface" % sbtVersion % "provided"
  //val sbtLauncher    = "org.scala-sbt" % "launcher" % sbtVersion

  //val aether         = "org.sonatype.aether" % "aether" % "1.13.1"
  val mvnAether      = "org.apache.maven" % "maven-aether-provider" % "3.0.4"
  val aetherWagon    = "org.sonatype.aether" % "aether-connector-wagon" % "1.13.1"
}
