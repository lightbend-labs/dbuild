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
  def extractDependencies(config: ProjectBuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    val mc = mvnConfig(config)
    val pom = 
      if(mc.directory.isEmpty) dir / "pom.xml"
      else  dir / mc.directory / "pom.xml"
    DependencyExtractor extract pom
  }
  
  
  def mvnConfig(config: ProjectBuildConfig) =
    config.extra match {
      case Some(ec) => ec
      case None => ExtraConfig()
    }
  
  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, log: logging.Logger): BuildArtifacts = {
    log.info("Running maven...")
    val mc = mvnConfig(project.config)
    val pom = 
      if(mc.directory.isEmpty) dir / "pom.xml"
      else  dir / mc.directory / "pom.xml"
    // TODO - Fix up project poms.
    // TODO - Allow directory/pom specification for Mvn.
    val result = MvnBuilder.runBuild(pom, input.artifacts.localRepo, log)
    if(result.hasExceptions()) {
      result.getExceptions.asScala foreach (t => log.trace(t))
    } else log.info("DONE!")
    BuildArtifacts(Seq.empty, null)
  }
}