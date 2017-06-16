libraryDependencies += Dispatch.dispatch

//addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.4.0")

libraryDependencies ++= {
  val dependencies = Seq("com.typesafe.sbt" % "sbt-site" % "0.7.0",
                         "com.typesafe.sbt" % "sbt-ghpages" % "0.5.2",
                         "com.typesafe.sbt" % "sbt-native-packager" % "0.8.0",
                         "com.typesafe.sbt" % "sbt-s3" % "0.9")
  val sbtV = (sbtBinaryVersion in update).value
  val scalaV = (scalaBinaryVersion in update).value
  if (scalaV.startsWith("2.10")) 
    dependencies map { sbt.Defaults.sbtPluginExtra(_, sbtV, scalaV) } else Seq.empty
}

