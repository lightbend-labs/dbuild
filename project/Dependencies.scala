import sbt._

class CommonDependencies {

  val mvnVersion = "3.2.3"

  val typesafeConfig = "com.typesafe" % "config" % "1.2.1"

  val dispatch       = "net.databinder.dispatch" %% "dispatch-core" % "0.12.0"

  val aetherVersion = "1.0.0.v20140518"
  val aether         = "org.eclipse.aether" % "aether" % aetherVersion
  val aetherApi      = "org.eclipse.aether" % "aether-api" % aetherVersion
  val aetherSpi      = "org.eclipse.aether" % "aether-spi" % aetherVersion
  val aetherUtil     = "org.eclipse.aether" % "aether-util" % aetherVersion
  val aetherImpl     = "org.eclipse.aether" % "aether-impl" % aetherVersion
  val aetherConnectorBasic = "org.eclipse.aether" % "aether-connector-basic" % aetherVersion
  val aetherFile     = "org.eclipse.aether" % "aether-transport-file" % aetherVersion
  val aetherHttp     = "org.eclipse.aether" % "aether-transport-http" % aetherVersion
  val aetherWagon    = "org.eclipse.aether" % "aether-transport-wagon" % aetherVersion

//  val ivy            = "org.scala-sbt.ivy" % "ivy" % "2.3.0-sbt-2cc8d2761242b072cedb0a04cb39435c4fa24f9a"
  val ivy            = "org.scala-sbt.ivy" % "ivy" % "2.3.0-sbt-48dd0744422128446aee9ac31aa356ee203cc9f4"

  val mvnAether      = "org.apache.maven" % "maven-aether-provider" % mvnVersion
  val mvnWagon       = "org.apache.maven.wagon" % "wagon-http" % "2.2"
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

  val jgit           = "org.eclipse.jgit" % "org.eclipse.jgit" % "3.1.0.201310021548-r"

  val slf4jSimple    = "org.slf4j" % "slf4j-simple" % "1.7.7"

}
