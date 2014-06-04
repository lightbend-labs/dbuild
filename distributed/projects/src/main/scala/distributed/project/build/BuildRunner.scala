package distributed
package project
package build

import java.io.File
import model._
import distributed.project.dependencies.Extractor
import distributed.project.model.ProjectBuildConfig

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
  def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, localBuildRunner: LocalBuildRunner,
      buildData:BuildData): BuildArtifactsOut
    
  /**
   * Determines the appropriate base for a project-specific ".dbuild" directory
   * for a project in "dir", configured according to "config".
   * 
   * @param dir The dir containing the checkout of the project
   * @param config The configuration record of this project
   */
  def projectDbuildDir(dir: File, proj: RepeatableProjectBuild): File
}

/** Aggregate builder. */
class AggregateBuildRunner(systems: Seq[BuildSystem[Extractor, LocalBuildRunner]]) extends BuildRunner {
  private def findBuildSystem(proj: ProjectBuildConfig): BuildSystem[Extractor, LocalBuildRunner] =
    BuildSystem.forName(proj.system, systems)

  def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, localBuildRunner: LocalBuildRunner,
      buildData:BuildData): BuildArtifactsOut =
    findBuildSystem(b.config).runBuild(b, dir, input, localBuildRunner, buildData)

  def projectDbuildDir(dir:File, proj: RepeatableProjectBuild): File =
    findBuildSystem(proj.config).projectDbuildDir(dir, proj)
}
