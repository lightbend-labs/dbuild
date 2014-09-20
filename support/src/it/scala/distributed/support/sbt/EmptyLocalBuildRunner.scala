package com.typesafe.dbuild
package support
package sbt

import java.io.File

import com.typesafe.dbuild.logging.{ConsoleLogger, Logger}
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.BuildData
import com.typesafe.dbuild.project.build.LocalBuildRunner


/** A local build runner we can use when testing that does absoultely nothing but throw on usage.
  *
  * Verifies that a given build system is not recursive.
  */
object EmptyLocalBuildRunner extends LocalBuildRunner(null, null, null) {
  override def checkCacheThenBuild(target: File,
                                   build: RepeatableProjectBuild,
                                   outProjects: Seq[Project],
                                   children: Seq[BuildOutcome],
                                   buildData: BuildData): BuildOutcome = sys.error("Local nested builds not supported in integration tests.")
  override def runLocalBuild(target: File,
                             build: RepeatableProjectBuild,
                             outProjects: Seq[Project],
                             buildData: BuildData): BuildArtifactsOut =sys.error("Local nested builds not supported in integration tests.")

}