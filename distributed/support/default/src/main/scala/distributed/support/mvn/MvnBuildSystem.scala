package distributed
package support
package mvn

import project.BuildSystem
import project.model._
import _root_.java.io.File
import logging.Logger
import _root_.sbt.Path._
import collection.JavaConverters._
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner

object MvnBuildSystem extends BuildSystemCore {
  val name = "maven"
  type ExtraType = MavenExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
      case Some(ec:MavenExtraConfig) => ec
      case None => MavenExtraConfig()
      case _ => throw new Exception("Internal error: Maven build config options have the wrong type. Please report")
    }

  def extractDependencies(config: ExtractionConfig, dir: File, extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val mc = config.extra[ExtraType]
    val pom = 
      if(mc.directory.isEmpty) dir / "pom.xml"
      else  dir / mc.directory / "pom.xml"
    DependencyExtractor extract pom
  }
  
  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut = {
    log.info("Running maven...")
    val mc = project.extra[ExtraType]
    val pom = 
      if(mc.directory.isEmpty) dir / "pom.xml"
      else  dir / mc.directory / "pom.xml"
    // TODO - Fix up project poms.
    // TODO - Allow directory/pom specification for Mvn.
    val result = MvnBuilder.runBuild(pom, input.artifacts.localRepo, log)
    if(result.hasExceptions()) {
      result.getExceptions.asScala foreach (t => log.trace(t))
    } else log.info("DONE!")
    BuildArtifactsOut(Seq.empty)
  }
}