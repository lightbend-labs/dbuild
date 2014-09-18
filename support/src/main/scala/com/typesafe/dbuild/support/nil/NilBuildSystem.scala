package com.typesafe.dbuild.support.nil

import com.typesafe.dbuild.model.NilExtraConfig
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import java.io.File
import com.typesafe.dbuild.logging
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.build.LocalBuildRunner

/** The Nil build system does exactly nothing */
object NilBuildSystem extends BuildSystemCore {
  val name: String = "nil"  
  type ExtraType = NilExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => NilExtraConfig() // pick default values
    case Some(ec:NilExtraConfig) => ec
    case _ => throw new Exception("Internal error: Nil build config options have the wrong type. Please report.")
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

    BuildArtifactsOut(meta.projects map {
      proj =>
        BuildSubArtifactsOut(proj.name, Seq.empty, Seq.empty,
          com.typesafe.dbuild.manifest.ModuleInfo(organization = proj.organization,
            name = proj.name, version = version, com.typesafe.dbuild.manifest.ModuleAttributes(None, None)))
    })
  }

  /** 
   * Fabricate some ExtractedBuildMeta as suitable.
   */
  private def readMeta(config: ProjectBuildConfig): ExtractedBuildMeta = {
    ExtractedBuildMeta(config.setVersion getOrElse "xxx", Seq.empty, Seq.empty)
  }
}
