package com.typesafe.dbuild.build

//
// Warning: This test relies on the "build" module, that is currently only compiled
// for 0.12/2.9. Therefore, "^^0.12" must be issued before "build/it:test" or "it:test",
// otherwise this test might be inadvertently skipped.
//

import com.typesafe.dbuild.logging.{ConsoleLogger, Logger}
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import org.specs2.mutable.Specification
import java.io.File
import com.typesafe.config.ConfigFactory
import com.typesafe.dbuild.model.Utils.readValueT
import com.typesafe.dbuild.model.DBuildConfiguration

// TDOO - Because this requires the sbt plugin to be published, we have to publsh locally
// before we can run integration tests.
object RewireSpec extends Specification {
  "dbuild" should {
    "inject into the meta-meta-build" in {
      sbt.IO.withTemporaryDirectory { projectDir =>

        val config = 
          """|build: {
             |  check-missing: [false, false, false]
             |  cross-version: standard
             |  space: test
             |  sbt-version: "0.13.7-M1"
             |  extraction-version: "2.11.1"
             |  projects: [
             |    {
             |      name: InjectionTest
             |      check-missing: false
             |      uri: "nil://"
             |      extra: {
             |        settings: [
             |          [],
             |          [],
             |          [
             |            "name := \"Hello-""".stripMargin + System.currentTimeMillis + """\""
             |          ]
             |        ]
             |        run-tests: false
             |      }
             |    }
             |  ]
             |}
             |""".stripMargin

        val endConfig = ConfigFactory.parseString(config)
        val conf = readValueT[DBuildConfiguration](endConfig)

        val repoStrings = Seq(
          "local", // this will point to <top>/.dbuild/topIvy/ivy2/local
          "maven-central",
          // this is where "publishLocal" pushed its modules (at least if the Ivy cache is in the default location)
          "localIvy: file:"+System.getProperty("user.home")+"/.ivy2/local, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
          "sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots",
          "sonatype-releases: https://oss.sonatype.org/content/repositories/releases",
          "java-annoying-cla-shtuff: http://download.java.net/maven/2/",
          "typesafe-releases: http://repo.typesafe.com/typesafe/releases",
          "typesafe-ivy-releases: http://repo.typesafe.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
          "typesafe-ivy-snapshots: http://repo.typesafe.com/typesafe/ivy-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
          "sbt-plugin-releases: http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
          "jgit-repo: http://download.eclipse.org/jgit/maven",
          "scala-fresh-2.10.x: http://repo.typesafe.com/typesafe/scala-fresh-2.10.x/"
        )
        val repoRecords = repoStrings map {
                        _.split(":", 2) match {
                          case Array(x) => (x, None)
                          case Array(x, y) => (x, Some(y))
                          case z => sys.error("Internal error, unexpected split result: " + z)
                        }}

        val repos = {
          val listMap = xsbt.boot.ListMap(repoRecords.toSeq.reverse: _*)
          // getRepositories contains a ListMap.toList, where sbt's definition
          // of toList is "backing.reverse". So we have to reverse again.
          (new xsbt.boot.ConfigurationParser).getRepositories(listMap)
        }

        val main = new LocalBuildMain(repos, BuildRunOptions(CleanupOptions(), true, true))
        val outcome = try {
          main.build(conf, "InjectionTest", None)
        } finally main.dispose()

        // TODO - We expect no output, but we do expet a successfull run.
        outcome.status.toString must equalTo("SUCCESS (project rebuilt ok)")
      }
    }
  }
}
