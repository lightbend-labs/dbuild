package distributed
package project

import model._

/** Runs a build in the given directory. */
trait BuildRunner {
  def system: String
  def runBuild(b: Build, dir: java.io.File, log: logging.Logger): Unit
}

/** Aggregate builder. */
class AggregateBuildRunner(runners: Seq[BuildRunner]) extends BuildRunner {
  def system = "all"
  def runBuild(b: Build, dir: java.io.File, log: logging.Logger): Unit = {
    val runner = runners find (_.system == b.config.system) getOrElse sys.error("Could not find build runner for " + b.config.system)
    runner.runBuild(b, dir, log)
  }
}
