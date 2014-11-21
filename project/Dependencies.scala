import sbt._


object Dependencies {

  val sbtVersion12 = "0.12.4"
  val sbtVersion13 = "0.13.0"

  val mvnVersion = "3.2.3"
  val aetherVersion = "1.0.0.v20140518"

  val typesafeConfig = "com.typesafe" % "config" % "1.2.1"
  val akkaActor29      = "com.typesafe.akka" % "akka-actor" % "2.0.5"
  val akkaActor210     = "com.typesafe.akka" %% "akka-actor" % "2.1.4"

// 2.1.1 per 2.10, 1.12.4.1 per 2.9.x. 1.12.3 is the only common one
//  val specs2         = "org.specs2" %% "specs2" % "2.1.1" % "test"
  val specs2         = "org.specs2" %% "specs2" % "1.12.3" % "it,test"

  val dispatch       = "net.databinder" %% "dispatch-http" % "0.8.10"

  val aether         = "org.eclipse.aether" % "aether" % aetherVersion
  val aetherApi      = "org.eclipse.aether" % "aether-api" % aetherVersion
  val aetherSpi      = "org.eclipse.aether" % "aether-spi" % aetherVersion
  val aetherUtil     = "org.eclipse.aether" % "aether-util" % aetherVersion
  val aetherImpl     = "org.eclipse.aether" % "aether-impl" % aetherVersion
  val aetherConnectorBasic = "org.eclipse.aether" % "aether-connector-basic" % aetherVersion
  val aetherFile     = "org.eclipse.aether" % "aether-transport-file" % aetherVersion
  val aetherHttp     = "org.eclipse.aether" % "aether-transport-http" % aetherVersion
  val aetherWagon    = "org.eclipse.aether" % "aether-transport-wagon" % aetherVersion

  val mvnAether      = "org.apache.maven" % "maven-aether-provider" % mvnVersion
  val mvnWagon       = "org.apache.maven.wagon" % "wagon-http" % "2.2"
  val mvnEmbedder    = "org.apache.maven" % "maven-embedder" % mvnVersion

  val jacks          = "com.cunei" %% "jacks" % "2.1.9"
  val jackson        = "com.fasterxml.jackson.core" % "jackson-annotations" % "2.1.4"
  val aws            = "com.amazonaws" % "aws-java-sdk" % "1.3.29"
  val uriutil        = "org.eclipse.equinox" % "org.eclipse.equinox.common" % "3.6.0.v20100503"

  val gpgLib         = "com.jsuereth" %% "gpg-library" % "0.8.2"
  val javaMail       = "javax.mail" % "mail" % "1.4.7"
  val commonsLang    = "commons-lang" % "commons-lang" % "2.6"
  val commonsIO      = "commons-io" % "commons-io" % "2.4"
  val jsch           = "com.jcraft" % "jsch" % "0.1.50"
  val oro            = "org.apache.servicemix.bundles" % "org.apache.servicemix.bundles.oro" % "2.0.8_6"
  val scallop        = "org.rogach" %% "scallop" % "0.9.5"

  val jgit           = "org.eclipse.jgit" % "org.eclipse.jgit" % "3.1.0.201310021548-r"

  val slf4jSimple    = "org.slf4j" % "slf4j-simple" % "1.7.7"

  def sbtIo(v:String)          = "org.scala-sbt" % "io" % v
  def sbtIvy(v:String)         = "org.scala-sbt" % "ivy" % v
  def sbtLogging(v:String)     = "org.scala-sbt" % "logging" % v
  def sbtLaunchInt(v:String)   = "org.scala-sbt" % "launcher-interface" % v % "provided"
//  def sbtCollections(v:String)     = "org.scala-sbt" % "collections" % v
  def sbtLauncher(v:String)    = "org.scala-sbt" % "launcher" % v


}
