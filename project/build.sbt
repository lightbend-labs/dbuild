addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.2")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.3")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.2-1")

libraryDependencies ++= {
  val dependencies = Seq(
                         "com.typesafe.sbt" % "sbt-site" % "1.3.1",
                         "com.typesafe.sbt" % "sbt-ghpages" % "0.6.2",
                         "com.typesafe.sbt" % "sbt-s3" % "0.9")
  val sbtV = (sbtBinaryVersion in update).value
  val scalaV = (scalaBinaryVersion in update).value
  if (scalaV.startsWith("2.10"))
    dependencies map { sbt.Defaults.sbtPluginExtra(_, sbtV, scalaV) } else Seq.empty
}
