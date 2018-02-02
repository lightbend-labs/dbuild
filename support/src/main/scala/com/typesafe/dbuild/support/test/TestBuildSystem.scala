package com.typesafe.dbuild.support.test

import com.typesafe.dbuild.model.TestExtraConfig
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import java.io.File
import com.typesafe.dbuild.logging
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.utils.TrackedProcessBuilder

/** The Test build system does essentially nothing; it just fails every now and then. It is used for testing */
object TestBuildSystem extends BuildSystemCore {
  val name: String = "test"  
  type ExtraType = TestExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => TestExtraConfig() // pick default values
    case Some(ec:TestExtraConfig) => ec
    case _ => throw new Exception("Internal error: Test build config options have the wrong type. Please report.")
  }

  def extractDependencies(config: ExtractionConfig, tracker: TrackedProcessBuilder,
      dir: File, extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val ec = config.extra[ExtraType]
    val meta=readMeta(config.buildConfig)
    val projects=meta.projects map {_.name}
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    meta
  }

  def runBuild(project: RepeatableProjectBuild, tracker: TrackedProcessBuilder, dir: File,
      input: BuildInput, localBuildRunner: LocalBuildRunner, buildData: BuildData): BuildArtifactsOut = {
    val ec = project.extra[ExtraType]

    val version = input.version
    val meta=readMeta(project.config)
    buildData.log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))

    // fail once every three runs on average
    val rand = new java.util.Random
    if (rand.nextInt(3)==0) throw new Exception("You've been unlucky today..!")
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
    ExtractedBuildMetaH(config.setVersion getOrElse "xxx", Seq.empty, Seq.empty)
  }
}
