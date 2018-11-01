import sbt._
import DbuildLauncher._

object Dependencies {

  val mvnVersion = "3.5.2"

  val typesafeConfig = "com.typesafe" % "config" % "1.2.1"

  val aetherVersion = "1.1.0"
  val aether         = "org.apache.maven.resolver" % "maven-resolver" % aetherVersion
  val aetherApi      = "org.apache.maven.resolver" % "maven-resolver-api" % aetherVersion
  val aetherSpi      = "org.apache.maven.resolver" % "maven-resolver-spi" % aetherVersion
  val aetherUtil     = "org.apache.maven.resolver" % "maven-resolver-util" % aetherVersion
  val aetherImpl     = "org.apache.maven.resolver" % "maven-resolver-impl" % aetherVersion
  val aetherConnectorBasic = "org.apache.maven.resolver" % "maven-resolver-connector-basic" % aetherVersion
  val aetherFile     = "org.apache.maven.resolver" % "maven-resolver-transport-file" % aetherVersion
  val aetherHttp     = "org.apache.maven.resolver" % "maven-resolver-transport-http" % aetherVersion
  val aetherWagon    = "org.apache.maven.resolver" % "maven-resolver-transport-wagon" % aetherVersion

  val ivy            = "org.scala-sbt.ivy" % "ivy" % "2.3.0-sbt-48dd0744422128446aee9ac31aa356ee203cc9f4"

  val mvnAether      = "org.apache.maven" % "maven-resolver-provider" % mvnVersion
  val mvnWagon       = "org.apache.maven.wagon" % "wagon-http" % "3.0.0"
  val mvnEmbedder    = "org.apache.maven" % "maven-embedder" % mvnVersion

  val jacks          = "com.cunei" %% "jacks" % "2.2.5"
  val jackson        = "com.fasterxml.jackson.core" % "jackson-annotations" % "2.2.3"
  val aws            = "com.amazonaws" % "aws-java-sdk" % "1.3.29"
  val uriutil        = "org.eclipse.equinox" % "org.eclipse.equinox.common" % "3.6.0.v20100503"
  val jline          = "jline" % "jline" % "2.14.2"

  val javaMail       = "javax.mail" % "mail" % "1.4.7"
  val commonsLang    = "commons-lang" % "commons-lang" % "2.6"
  val commonsIO      = "commons-io" % "commons-io" % "2.4"
  val jsch           = "com.jcraft" % "jsch" % "0.1.50"
  val oro            = "org.apache.servicemix.bundles" % "org.apache.servicemix.bundles.oro" % "2.0.8_6"
  val scallop        = "org.rogach" %% "scallop" % "1.0.0"

  val slf4jSimple    = "org.slf4j" % "slf4j-simple" % "1.7.7"


  // these dependencies change depending on the scala version

  def akkaActor(scala212: Boolean) =
    if (scala212)
      "com.typesafe.akka" %% "akka-actor" % "2.4.17"
    else
      "com.typesafe.akka" %% "akka-actor" % "2.3.16"

  def specs2(scala212: Boolean) =
    if (scala212)
      "org.specs2" %% "specs2-core" % "3.8.8" % "it,test"
    else
      "org.specs2" %% "specs2" % "2.1.1" % "it,test"

  def dispatch(scala212: Boolean) =
    if (scala212)
      "net.databinder.dispatch" %% "dispatch-core" % "0.12.2"
    else
      "net.databinder.dispatch" %% "dispatch-core" % "0.11.3"

  // Once new versions of sbt/launcher/libraryManagement/zinc etc are released, move the 2.12 dependencies to those versions
  // The sbt modules dependend on both the scala version and the sbt version; sometimes they are "provided", but not always

  def sbtIo(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "io" % "1.0.2"
    else
      "org.scala-sbt" % "io" % v

  def sbtIvy(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "librarymanagement-ivy" % "1.0.4"
    else
      "org.scala-sbt" % "ivy" % v

  def sbtLogging(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "util-logging" % "1.0.3"
    else
      "org.scala-sbt" % "logging" % v

  def sbtCommand(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "command" % v
    else
      "org.scala-sbt" % "command" % v

  def sbtSbt(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" % "sbt" % v
    else
      "org.scala-sbt" % "sbt" % v

  // We deal with two separate launchers:
  // 1) The "sbt-launch.jar" is the regular sbt launcher. we package it in the "build" subproject
  // as a resource, so that it is available to the running dbuild when it wants to spawn a further sbt.
  // 2) We use a modified, dbuild-specific modified version in order to launch dbuild. This
  // is necessary since the Proguard-optimized sbt launcher is unusable as a library. This is the
  // version herebelow. The dependencies are not always provided, so we handle them like sbt dependencies,
  // even though they do not really change on scalaversion/sbtversion

  def dbuildLaunchInt(scala212: Boolean, v:String) = launchInt
  def dbuildLauncher(scala212: Boolean, v:String) = launcher

  // other dependencies that depend on whether scala is 2.10 or 2.12, but are only included in some cases
  def zincIf212(scala212: Boolean, v:String): Option[ModuleID] =
    if (scala212)
      Some("org.scala-sbt" %% "zinc" % "1.0.5")
    else
      None

  def gpgLibIf210(scala212: Boolean): Option[ModuleID] =
    if (scala212)
      None
    else
      Some("com.jsuereth" %% "gpg-library" % "0.8.2")

}
