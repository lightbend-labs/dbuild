package distributed
package build

import project.model._
import java.io.File
import sbt.Path._

/** Takes a given build configuration and returns a *repeatable* full realizes build
 * configuration.
 */
trait BuildAnalyzer {
  /** Takes a build configuration and returns an executable build configuration.
   * The returned configuration lists *full* build configuration in 
   * an appropriate build order.
   */
  def analyze(target: File, config: DistributedBuildConfig, log: logging.Logger): RepeatableDistributedBuild 
}

/** Simple implementation that delegates to an extractor. */
class SimpleBuildAnalyzer(e: project.dependencies.Extractor) extends BuildAnalyzer {
  final def analyze(target: File, config: DistributedBuildConfig, log: logging.Logger): RepeatableDistributedBuild = {
    val scratchDir = local.ProjectDirs.makeDirForBuild(config, target / "extraction")
    
    val builds = config.projects map (p => e.extract(scratchDir, p, log))
    RepeatableDistributedBuild(builds)
  }
}

//object BuildAnalyzer extends SimpleBuildAnalyzer(project.dependencies.Extractor)
