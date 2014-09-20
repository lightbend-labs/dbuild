package com.typesafe.dbuild
package support
package sbt

import com.typesafe.dbuild.logging.{ConsoleLogger, Logger}
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import org.specs2.mutable.Specification
import java.io.File


// TDOO - Because this requires the sbt plugin to be published, we have to publsh locally
// before we can run integration tests.
object SbtRewireSpec extends Specification {
  import SbtTestHarness._
  "SbtBuildSystem" should {
    "inject into the meta-meta-build" in {
      withBuildSystem { case SbtTestConfig(buildSystem, projectDir) =>
        val inRepo = new File(projectDir, "in-repo")
        val outRepo = new File(projectDir, "out-repo")
        val sbtVersion = "0.13.5"
        writeBuildProperties(projectDir, sbtVersion)
        writeMetaPluginsFile(projectDir)(
          """|name := "test"
             |""".stripMargin)
        writeBuildFile(projectDir)(
          """|scalaVersion := "2.10.4"
            |
            |name := "test"
            |
            |version := "1.0"
            |""".stripMargin)
        val stdOutLogger: Logger = ConsoleLogger(true)
        // TODO - Talk to Toni about cleaning this up/adding helpers.
        val build = RepeatableProjectBuild(
          ProjectConfigAndExtracted(
            ProjectBuildConfig(
              name = "test-project",
              setVersion = None,
              setVersionSuffix = None,
              crossVersion = Some(Seq[String]()),
              checkMissing = Some(Seq(false, false, false)),
              extra = Some(SbtExtraConfig(sbtVersion = Some(sbtVersion),
                extractionVersion = Some(sbtVersion),
                commands = Seq(),
                settings = SeqSeqString(Seq(
                  Seq(),
                  Seq(),
                  Seq("name := \"hi\"")
                ))))
            ),
            ExtractedBuildMeta(Seq())
          ),
          Seq()
        )
        val data = BuildData(stdOutLogger, debug = true)

        val input = BuildInput(
          artifacts = BuildArtifactsInMulti(Seq(
            BuildArtifactsIn(Nil, "default-space", inRepo)
          )),
          version = "",
          subproj= Seq[Seq[String]] (),
          outRepo = outRepo                                                   ,
          projectName = "test-project"
        )

        val result = buildSystem.runBuild(
          build,
          projectDir,
          input,
          EmptyLocalBuildRunner,
          data
        )
        // TODO - We expect no output, but we do expet a successfull run.
        result must equalTo(BuildArtifactsOut(List()))
      }
    }
  }
}