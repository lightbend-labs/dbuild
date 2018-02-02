package com.typesafe.dbuild.project.build

import java.io.File
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project._
import com.typesafe.dbuild.model.ProjectBuildConfig
import com.typesafe.dbuild.utils.TrackedProcessBuilder

/** Runs a build in the given directory. */
trait BuildRunner {
  /**
   * Runs the build for a project in the given directory with a set of incoming
   * repository information.
   * 
   * @param b The project to build
   * @param dir The directory in which to run the build (should be already resolved)
   * @param input Information about artifact read/write repositories and versions.
   * @param localBuildRunner The LocalBuildRunner currently in use (for nested calls)
   * @param log  The log to write to.
   */
  def runBuild(b: RepeatableProjectBuild, tracker: TrackedProcessBuilder, dir: java.io.File,
               input: BuildInput, localBuildRunner: LocalBuildRunner, buildData:BuildData): BuildArtifactsOut
}

/** Aggregate builder. */
class AggregateBuildRunner(systems: Seq[BuildSystem[Extractor, LocalBuildRunner]]) extends BuildRunner {
  private def findBuildSystem(proj: ProjectBuildConfig): BuildSystem[Extractor, LocalBuildRunner] =
    BuildSystem.forName(proj.system, systems)

  def runBuild(b: RepeatableProjectBuild, tracker: TrackedProcessBuilder, dir: java.io.File,
      input: BuildInput, localBuildRunner: LocalBuildRunner, buildData:BuildData): BuildArtifactsOut =
    findBuildSystem(b.config).runBuild(b, tracker, dir, input, localBuildRunner, buildData)
}
