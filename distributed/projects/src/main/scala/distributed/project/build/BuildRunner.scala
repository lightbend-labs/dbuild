package distributed
package project
package build

import java.io.File
import model._
import distributed.project.dependencies.Extractor

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
  def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut
    
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
  private def findBuildSystem(proj: distributed.project.model.ProjectBuildConfig): distributed.project.BuildSystem[Extractor, LocalBuildRunner] = {
    systems find (_.name == proj.system) getOrElse sys.error("Could not find build system for " + proj.system)
  }

  def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut =
    findBuildSystem(b.config).runBuild(b, dir, input, localBuildRunner, log)

  def projectDbuildDir(dir:File, proj: RepeatableProjectBuild): File =
    findBuildSystem(proj.config).projectDbuildDir(dir, proj)
}
