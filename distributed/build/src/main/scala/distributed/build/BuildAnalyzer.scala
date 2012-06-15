package distributed
package build

import project.model._
import graph.Graphs

/** Takes a given build configuration and returns a *repeatable* full realizes build
 * configuration.
 */
trait BuildAnalyzer {
  /** Takes a build configuration and returns an executable build configuration.
   * The returned configuration lists *full* build configuration in 
   * an appropriate build order.
   */
  def analyze(config: DistributedBuildConfig, log: logging.Logger): DistributedBuild 
}

/** Simple implementation that delegates to an extractor. */
class SimpleBuildAnalyzer(e: project.dependencies.Extractor) extends BuildAnalyzer {
  final def analyze(config: DistributedBuildConfig, log: logging.Logger): DistributedBuild = {
    val builds = config.projects map (p => e.extract(p, log))
    // Now we need them in build ordering...
    val graph = new BuildGraph(builds)
    val ordered = (Graphs safeTopological graph map (_.value)).reverse
    DistributedBuild(ordered)
  }
}

//object BuildAnalyzer extends SimpleBuildAnalyzer(project.dependencies.Extractor)
