package distributed.support.nil

import distributed.project.model.NilExtraConfig
import distributed.support.BuildSystemCore
import distributed.project.model._
import distributed.logging.Logger
import java.io.File
import distributed.logging
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner

/** The Nil build system does essentially nothing, and is used for testing */
object NilBuildSystem extends BuildSystemCore {
  val name: String = "nil"  

  private def nilExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => NilExtraConfig() // pick default values
    case Some(ec:NilExtraConfig) => ec
    case _ => throw new Exception("Internal error: Nil build config options are the wrong type in project \""+config.name+"\". Please report.")
  }

  def extractDependencies(config: ExtractionConfig, dir: File, extractor: Extractor, log: Logger): ExtractedBuildMeta = {
    val ec = nilExpandConfig(config.buildConfig)
    val meta=readMeta(config.buildConfig)
    val projects=meta.projects map {_.name}
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    meta
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut = {
    val ec = nilExpandConfig(project.config)

    val version = input.version
    val meta=readMeta(project.config)
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))

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
    ExtractedBuildMeta(config.setVersion getOrElse "xxx", Seq.empty, Seq.empty)
  }
}
