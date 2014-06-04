package distributed.support.test

import distributed.project.model.TestExtraConfig
import distributed.project.{ BuildSystem, BuildData }
import distributed.support.BuildSystemCore
import distributed.project.model._
import distributed.logging.Logger
import java.io.File
import distributed.logging
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner

/** The Test build system does essentially nothing; it just fails every now and then. It is used for testing */
object TestBuildSystem extends BuildSystemCore {
  val name: String = "test"  
  type ExtraType = TestExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => TestExtraConfig() // pick default values
    case Some(ec:TestExtraConfig) => ec
    case _ => throw new Exception("Internal error: Test build config options have the wrong type. Please report.")
  }

  def extractDependencies(config: ExtractionConfig, dir: File, extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val ec = config.extra[ExtraType]
    val meta=readMeta(config.buildConfig)
    val projects=meta.projects map {_.name}
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    meta
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, localBuildRunner: LocalBuildRunner,
      buildData: BuildData): BuildArtifactsOut = {
    val ec = project.extra[ExtraType]

    val version = input.version
    val meta=readMeta(project.config)
    buildData.log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))

    // fail once every three runs on average
    val rand = new java.util.Random
    if (rand.nextInt(3)==0) throw new Exception("You've been unlucky today..!")
    BuildArtifactsOut(meta.projects map {
      proj => BuildSubArtifactsOut(proj.name,Seq.empty,Seq.empty)})
  }

  /** 
   * Fabricate some ExtractedBuildMeta as suitable.
   */
  private def readMeta(config: ProjectBuildConfig): ExtractedBuildMeta = {
    new ExtractedBuildMeta(config.setVersion getOrElse "xxx", Seq.empty, Seq.empty)
  }
}
