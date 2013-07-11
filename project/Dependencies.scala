import sbt._


object Dependencies {

  val akkaVersion = "2.0.3"
  val sbtVersion = "0.12.4"
  val mvnVersion = "3.0.4"

  val typesafeConfig = "com.typesafe" % "config" % "0.4.1"
  val akkaActor      = "com.typesafe.akka" % "akka-actor" % akkaVersion

  val specs2         = "org.specs2" %% "specs2" % "1.10" % "test"

  val dispatch       = "net.databinder" % "dispatch-http_2.9.1" % "0.8.6"

  val sbtIo          = "org.scala-sbt" % "io" % sbtVersion
  val sbtLogging     = "org.scala-sbt" % "logging" % sbtVersion
  val sbtCollections     = "org.scala-sbt" % "collections" % sbtVersion
  val sbtLaunchInt   = "org.scala-sbt" % "launcher-interface" % sbtVersion % "provided"
//  val sbtLauncher    = "org.scala-sbt" % "launcher" % sbtVersion

  //val aether         = "org.sonatype.aether" % "aether" % "1.13.1"
  val mvnAether      = "org.apache.maven" % "maven-aether-provider" % mvnVersion
  val aetherWagon    = "org.sonatype.aether" % "aether-connector-wagon" % "1.13.1"
  val mvnWagon    = "org.apache.maven.wagon" % "wagon-http" % "2.2"
  val mvnEmbedder    = "org.apache.maven" % "maven-embedder" % mvnVersion

  val jacks          = "com.cunei" %% "jacks" % "2.1.9"
  val jackson        = "com.fasterxml.jackson.core" % "jackson-annotations" % "2.1.4"
  val aws            = "com.amazonaws" % "aws-java-sdk" % "1.3.29"
  val uriutil        = "org.eclipse.equinox" % "org.eclipse.equinox.common" % "3.6.0.v20100503"
}
