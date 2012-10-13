package distributed
package project
package build

import model._

/** Runs a build in the given directory. */
trait BuildRunner {
  /**
   * Runs the build for a project in the given directory with a set of incoming
   * repository information.
   * 
   * @param b The project to build
   * @param dir The directory in which to run the build (should be already resolved)
   * @param input Information about artifact read/write repositories and versions.
   * @param log  The log to write to.
   */
  def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, log: logging.Logger): BuildArtifacts
}

/** Aggregate builder. */
class AggregateBuildRunner(systems: Seq[BuildSystem]) extends BuildRunner {
  def system = "all"
  override def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, log: logging.Logger): BuildArtifacts = {
    val runner = systems find (_.name == b.config.system) getOrElse sys.error("Could not find build runner for " + b.config.system)
    runner.runBuild(b, dir, input, log)
  }
}
