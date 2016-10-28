// only if scalaVersion is 2.10:
//addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.4")
//addSbtPlugin("com.typesafe.sbt" % "sbt-s3" % "0.9")

libraryDependencies ++= {
  val dependencies = Seq("com.typesafe.sbt" % "sbt-native-packager" % "0.8.0",
                         "com.typesafe.sbt" % "sbt-s3" % "0.9")
  val sbtV = (sbtBinaryVersion in update).value
  val scalaV = (scalaBinaryVersion in update).value
  if (scalaV.startsWith("2.10")) 
    dependencies map { sbt.Defaults.sbtPluginExtra(_, sbtV, scalaV) } else Seq.empty
}

