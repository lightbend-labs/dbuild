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
  
  /**
   * Expands the build options (the 'extra' field) so that the defaults
   * that apply for this build system are taken into account.
   * 
   * @param proj The project configuration that should be expanded
   */
  def expandExtraDefaults(proj: ProjectBuildConfig): ProjectBuildConfig
}

/** Aggregate builder. */
class AggregateBuildRunner(systems: Seq[BuildSystem]) extends BuildRunner {
  def system = "all"
  override def runBuild(b: RepeatableProjectBuild, dir: java.io.File, input: BuildInput, log: logging.Logger): BuildArtifacts = {
    val runner = systems find (_.name == b.config.system) getOrElse sys.error("Could not find build runner for " + b.config.system)
    runner.runBuild(b, dir, input, log)
  }
  override def expandExtraDefaults(proj: ProjectBuildConfig): ProjectBuildConfig = {
    val runner = systems find (_.name == proj.system) getOrElse sys.error("Could not find build runner for " + proj.system)
    runner.expandDefaults(proj)    
  }
}
