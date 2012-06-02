name := "sbt-uber-builder"

organization := "com.typesafe"

scalaVersion := "2.9.1"

libraryDependencies += "com.typesafe" % "config" % "0.4.1"

libraryDependencies += "org.specs2" %% "specs2" % "1.10" % "test"

libraryDependencies <+= sbtVersion apply (v => "org.scala-sbt" %% "io" % v) 
