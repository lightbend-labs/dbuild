package com.typesafe.dbuild
package support
package sbt

import com.typesafe.dbuild.logging.{ConsoleLogger, Logger}
import com.typesafe.dbuild.model.{ProjectRef, SbtExtraConfig, ProjectBuildConfig, ExtractionConfig}
import org.specs2.mutable.Specification


// TDOO - Because this requires the sbt plugin to be published, we have to publsh locally
// before we can run integration tests.
object SbtExtractionSpec extends Specification {
  import SbtTestHarness._
  "SbtBuildSystem" should {
    "extract dependencies" in {
      withBuildSystem { case SbtTestConfig(buildSystem, projectDir) =>
         val sbtVersion = "0.13.5"
         writeBuildProperties(projectDir, sbtVersion)
         writePluginsFile(projectDir)(
           """|organization := "test"
              |
              |name := "test-build"
              |
              |libraryDependencies += "com.typesafe" % "config" % "1.2.1"
              |""".stripMargin)
         writeBuildFile(projectDir)(
           """|scalaVersion := "2.10.4"
              |
              |name := "test"
              |
              |version := "1.0"
              |
              |organization := "test"
              |
              |val test1 = project.settings(
              |  organization := "test",
              |  version := "1.1"
              |)
              |
              |val test2 = project.dependsOn(test1).settings(
              |  organization := "test",
              |  version := "1.2",
              |  libraryDependencies += "junit" % "junit" % "4.11"
              |)
              |""".stripMargin)
         val stdOutLogger: Logger = ConsoleLogger(true)
         val config: ExtractionConfig =
           ExtractionConfig(ProjectBuildConfig(
             name = "test-sbt",
             setVersion = None,
             setVersionSuffix = None,
             extra = Some(
               SbtExtraConfig(
                 sbtVersion = Some(sbtVersion),
                 extractionVersion = Some("2.10.4"),
                 commands = Nil,
                 settings = Nil))))

         val result = buildSystem.extractDependencies(config, projectDir, null /* Extractor */, stdOutLogger, true)
         // Here there are implicit build levels.  projInfo is split into an arbitrary number of levels, each
         // denoting one of the sbt project levels.  For example:
         //  Index - Project
         //  0  - root build  defined in ./
         //  1  - meta build, defined in ./project/
         //  2 -  meta meta build, defined in ./project/project
         val Seq(prodProjects, buildProjects) = result.projInfo

         // TODO - more specific tests here.
         prodProjects.projects.map(p => (p.organization, p.name)) must containAllOf(
           Seq(("test", "test"), ("test", "test1"), ("test", "test2"))
         )
         buildProjects.projects.map(p => (p.organization, p.name)) must containAllOf(
           Seq(("test", "test-build"))
         )
         buildProjects.projects.flatMap(_.dependencies) must containAllOf(
           // TODO - Why isn't sbt showing up as a dependency?
           Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("config", "com.typesafe"))
         )
         prodProjects.projects.flatMap(_.dependencies) must containAllOf(
            Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("junit", "junit"))
         )
      }
    }
  }
}