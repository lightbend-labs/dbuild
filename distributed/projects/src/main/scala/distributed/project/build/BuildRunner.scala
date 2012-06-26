package distributed
package project
package build

import model._

/** Runs a build in the given directory. */
trait BuildRunner {
  def runBuild(b: Build, dir: java.io.File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts
}

/** Aggregate builder. */
class AggregateBuildRunner(systems: Seq[BuildSystem]) extends BuildRunner {
  def system = "all"
  override def runBuild(b: Build, dir: java.io.File, deps: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    val runner = systems find (_.name == b.config.system) getOrElse sys.error("Could not find build runner for " + b.config.system)
    runner.runBuild(b, dir,deps, log)
  }
}
