package distributed
package support
package mvn

import distributed.project.BuildSystem
import distributed.project.model._
import _root_.java.io.File
import distributed.logging.Logger
import _root_.sbt.Path._
import collection.JavaConverters._
object MvnBuildSystem extends BuildSystem {
  val name = "maven"
  def extractDependencies(config: BuildConfig, dir: File, log: Logger): ExtractedBuildMeta =
    // TODO - allow directory specification for Mvn.
    DependencyExtractor extract (dir / "pom.xml")
  def runBuild(project: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    log.info("Running maven...")
    // TODO - Allow directory/pom specification for Mvn.
    val result = MvnBuilder.runBuild(dir / "pom.xml", dependencies.localRepo, log)
    if(result.hasExceptions()) {
      result.getExceptions.asScala foreach (t => log.trace(t))
    } else log.info("DONE!")
    dependencies
  }
}