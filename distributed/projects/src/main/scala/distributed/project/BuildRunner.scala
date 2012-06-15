package distributed
package project

import model._

/** Runs a build in the given directory. */
trait BuildRunner {
  def system: String
  def runBuild(b: Build, dir: java.io.File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts
}

/** Aggregate builder. */
class AggregateBuildRunner(runners: Seq[BuildRunner]) extends BuildRunner {
  def system = "all"
  override def runBuild(b: Build, dir: java.io.File, deps: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    val runner = runners find (_.system == b.config.system) getOrElse sys.error("Could not find build runner for " + b.config.system)
    runner.runBuild(b, dir,deps, log)
  }
}
