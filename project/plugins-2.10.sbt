// only if scalaVersion is 2.10:
//addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.7.0")
//addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.2")
//addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "0.8.0") // could be 1.1.4 now
//addSbtPlugin("com.typesafe.sbt" % "sbt-s3" % "0.9")
//addSbtPlugin("com.eed3si9n" % "bintray-sbt" % "0.3.0-a1934a5457f882053b08cbdab5fd4eb3c2d1285d")
// -> which is a patched version of addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")


resolvers += Resolver.url("bintray-eed3si9n-sbt-plugins", url("https://dl.bintray.com/eed3si9n/sbt-plugins/"))(Resolver.ivyStylePatterns)
resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"


libraryDependencies ++= {
  val dependencies = Seq("com.typesafe.sbt" % "sbt-site" % "0.7.0",
                         "com.typesafe.sbt" % "sbt-ghpages" % "0.5.2",
                         "com.typesafe.sbt" % "sbt-native-packager" % "0.8.0",
                         "com.typesafe.sbt" % "sbt-s3" % "0.9",
                         "com.eed3si9n" % "bintray-sbt" % "0.3.0-a1934a5457f882053b08cbdab5fd4eb3c2d1285d")
  val sbtV = (sbtBinaryVersion in update).value
  val scalaV = (scalaBinaryVersion in update).value
  if (scalaV.startsWith("2.10")) 
    dependencies map { sbt.Defaults.sbtPluginExtra(_, sbtV, scalaV) } else Seq.empty
}

