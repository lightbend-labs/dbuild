package com.typesafe.dbuild.project.dependencies

import com.typesafe.dbuild.adapter.Adapter
import Adapter.IO
import Adapter.Path._
import java.io.File
import com.typesafe.dbuild.project.resolve.ProjectResolver
import com.typesafe.dbuild.model.{ ProjectConfigAndExtracted, ProjectBuildConfig, ExtractedBuildMeta, SeqDepsModifiers }
import com.typesafe.dbuild.model.{ ExtractionOK, ExtractionOutcome, ExtractionFailed, ExtractionConfig, DepsModifiers }
import com.typesafe.dbuild.logging._
import com.typesafe.dbuild.repo.core.Repository
import com.typesafe.dbuild.model.Utils.{ writeValue, readValue }
import com.typesafe.dbuild.logging.Logger.prepareLogMsg
import org.apache.ivy.core.module.id.ModuleId
import com.typesafe.dbuild.model.ProjectRef
import com.typesafe.dbuild.project.cleanup.Recycling.{ updateTimeStamp, markSuccess }
import com.typesafe.dbuild.model.ExtractionOK
import org.apache.ivy.core.module.id.ModuleRevisionId
import com.typesafe.dbuild.model.ProjectRef
import com.typesafe.dbuild.model.SeqDepsModifiersH._
import com.typesafe.dbuild.model.SeqStringH._

/** This is used to extract dependencies from projects. */
class Extractor(
  val resolver: ProjectResolver,
  val dependencyExtractor: BuildDependencyExtractor,
  repository: Repository) {

  /**
   * Filter or modify the project dependencies, according to the specification
   * contained in DepsModifiers. It is presently used to ignore (and not rewire) certain
   * dependencies; that could be further extended in the future in order to
   * modify the project dependencies in other manners.
   *
   * For multi-level builds, at this time only the first level is changed. TODO: change
   * DepsModifier into an autowrapping object, and adapt multiple levels, if applicable.
   */
  def modifiedDeps(depsMods: SeqDepsModifiers, extractedDeps: ExtractedBuildMeta, log: Logger): ExtractedBuildMeta = {
    // we can have more depsMods than extracted levels, in which case we emit a warning
    if (depsMods.length > extractedDeps.projInfo.length)
      log.warn("** WARNING: There are more depedency modifier records than extracted dependency levels, the last " +
        (extractedDeps.projInfo.length - depsMods.length) + " will be ignored")
    // the last ones will remain unchanged:
    val unchanged = extractedDeps.projInfo.drop(depsMods.length)
    // the initial ones will be modified as follows:
    val modified = (extractedDeps.projInfo zip depsMods) map {
      case (extr, mod) =>
        val ignored = mod.ignore.map(ModuleId.parse)
        // are these ignored modules present? if not, print a warning
        val allRealDeps = extr.projects.flatMap(_.dependencies)
        def sameId(dep: ProjectRef, mod: ModuleId) =
          mod.getOrganisation == dep.organization && mod.getName == dep.name
        val notFound = ignored.filterNot(mod =>
          allRealDeps.exists(dep => sameId(dep, mod)))
        if (notFound.nonEmpty)
          log.warn(notFound.mkString("*** WARNING: These dependencies (marked as \"ignore\") were not found: ", ", ", ""))

        val modDeps = extr.copy(projects = extractedDeps.getHead.projects.map(proj =>
          proj.copy(dependencies = proj.dependencies.filterNot(dep =>
            ignored.exists(mod => sameId(dep, mod))))))
        val added = mod.inject.map(ModuleId.parse)
        val result = modDeps.copy(projects = modDeps.projects.map(proj =>
          proj.copy(dependencies = (proj.dependencies.++(added.map { d =>
            ProjectRef(d.getName, d.getOrganisation)
          }).distinct))))
        result
    }
    ExtractedBuildMeta(modified ++ unchanged)
  }

  /** Given an initial build configuration, extract *ALL* information needed for a full build. */
  def extract(tdir: File, extractionConfig: ExtractionConfig, logger: Logger, debug: Boolean): ExtractionOutcome = try {
    val build = extractionConfig.buildConfig
    ExtractionDirs.useProjectExtractionDirectory(extractionConfig, tdir) { dir =>
      updateTimeStamp(dir)
      // NB: while resolving projects:
      // extractor.resolver.resolve() only resolves the main URI,
      // extractor.dependencyExtractor.resolve() also resolves the nested ones, recursively
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = ExtractionConfig(dependencyExtractor.resolve(extractionConfig.buildConfig, dir, this, logger))
      config.buildConfig.getCommit foreach { s: String => logger.info("Commit: " + s) }
      logger.debug("Repeatable Config: " + writeValue(config))
      val outcome = extractedResolvedWithCache(config, dir, logger, debug)
      outcome match {
        case _: ExtractionOK => markSuccess(dir)
        case _ =>
      }
      outcome
    }
  } catch {
    case e =>
      ExtractionFailed(extractionConfig.buildConfig.name, Seq.empty, prepareLogMsg(logger, e))
  }

  def extractedResolvedWithCache(config: ExtractionConfig, dir: File, logger: Logger, debug: Boolean): ExtractionOutcome = {
    // Here, we attempt to cache our extracted dependencies rather than do
    // resolution again.
    // TODO - This should be configurable!
    val build = config.buildConfig
    cachedExtractOr(config, logger) {
      logger.info("Extracting dependencies for: " + build.name)
      val extractedDeps = dependencyExtractor.extract(config, dir, this, logger, debug)
      // process deps.ignore clauses
      val deps = modifiedDeps(config.buildConfig.deps, extractedDeps, logger)
      logger.debug("Dependencies = " + writeValue(deps))
      cacheExtract(config, deps, logger)
      ExtractionOK(build.name, Seq.empty, Seq(ProjectConfigAndExtracted(config.buildConfig, deps)))
    }
  }

  private def makeExtractKey(config: ExtractionConfig) =
    "meta/extract/" + config.uuid

  private def cacheExtract(config: ExtractionConfig, extract: ExtractedBuildMeta, logger: Logger): Unit =
    IO.withTemporaryFile("extract", config.uuid) { file =>
      IO.write(file, writeValue(extract))
      logger.debug("Putting extraction information into: " + repository)
      repository.put(makeExtractKey(config), file)
    }

  def cachedExtractOr(config: ExtractionConfig, logger: Logger)(f: => ExtractionOutcome): ExtractionOutcome =
    try {
      val key = makeExtractKey(config)
      val file = repository.get(key)
      logger.debug("Dependencies are cached!")
      val deps = readValue[ExtractedBuildMeta](file)
      logger.debug("Dependencies = " + writeValue(deps))
      val baseLevelProjInfo = deps.getHead
      if (baseLevelProjInfo.subproj.nonEmpty)
        logger.info(baseLevelProjInfo.subproj.mkString("The following subprojects will be built in project " + config.buildConfig.name + ": ", ", ", ""))
      ExtractionOK(config.buildConfig.name, Seq.empty, Seq(ProjectConfigAndExtracted(config.buildConfig, deps)))
    } catch {
      case _: Exception => f
    }
}
