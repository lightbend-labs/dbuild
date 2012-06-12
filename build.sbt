name := "sbt-uber-builder"

organization := "com.typesafe"

scalaVersion := "2.9.1"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "spray" at "http://repo.spray.cc/"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "0.4.1",
  "com.typesafe.akka" % "akka-actor" % "2.0.1",
  "cc.spray" % "spray-io" % "1.0-M2",
  "org.specs2" %% "specs2" % "1.10" % "test")

libraryDependencies <++= sbtVersion apply { v => 
  Seq("org.scala-sbt" %% "io" % v,
      "org.scala-sbt" %% "logging" % v)
} 
