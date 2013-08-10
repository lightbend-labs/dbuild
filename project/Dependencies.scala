import sbt._


object Dependencies {

  val akkaVersion = "2.0.5"
  val sbtVersion = "0.12.4"
  val mvnVersion = "3.0.4"

  val typesafeConfig = "com.typesafe" % "config" % "1.0.2"
  val akkaActor      = "com.typesafe.akka" % "akka-actor" % akkaVersion

  val specs2         = "org.specs2" %% "specs2" % "1.10" % "test"

  val dispatch       = "net.databinder" %% "dispatch-http" % "0.8.10"

  val sbtIo          = "org.scala-sbt" % "io" % sbtVersion
  val sbtIvy         = "org.scala-sbt" % "ivy" % sbtVersion
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

  val gpgLib         = "com.jsuereth" %% "gpg-library" % "0.8.1"
  val javaMail       = "javax.mail" % "mail" % "1.4.7"
  val commonsLang    = "commons-lang" % "commons-lang" % "2.6"
  val commonsEmail   = "org.apache.commons" % "commons-email" % "1.3.1"
}
