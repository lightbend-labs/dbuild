package distributed
package project
package dependencies

import sbt.IO
import sbt.Path._
import java.io.File
import project.resolve.ProjectResolver
import model.{ ProjectConfigAndExtracted, ProjectBuildConfig, ExtractedBuildMeta }
import model.{ ExtractionOK, ExtractionOutcome, ExtractionFailed, ExtractionConfig, DepsModifiers }
import logging._
import repo.core.Repository
import distributed.project.model.Utils.{ writeValue, readValue }
import distributed.logging.Logger.prepareLogMsg
import org.apache.ivy.core.module.id.ModuleId
import distributed.project.model.ProjectRef
import distributed.project.cleanup.Recycling.{ updateTimeStamp, markSuccess }
import distributed.project.model.ExtractionOK
import org.apache.ivy.core.module.id.ModuleRevisionId
import distributed.project.model.ProjectRef

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
  def modifiedDeps(depsMods: Option[DepsModifiers], extractedDeps: ExtractedBuildMeta, log: logging.Logger): ExtractedBuildMeta = {
    depsMods match {
      case None => extractedDeps
      case Some(all) =>
        val ignored = all.ignore.map(ModuleId.parse)
        // are these ignored modules present? if not, print a warning
        val allRealDeps = extractedDeps.projects.flatMap(_.dependencies)
        def sameId(dep: ProjectRef, mod: ModuleId) =
          mod.getOrganisation == dep.organization && mod.getName == dep.name
        val notFound = ignored.filterNot(mod =>
          allRealDeps.exists(dep => sameId(dep, mod)))
        if (notFound.nonEmpty)
          log.warn(notFound.mkString("*** WARNING: These dependencies (marked as \"ignore\") were not found: ", ", ", ""))
        // TODO: add "deps" for EACH level
        val modDeps = extractedDeps.getHead.copy(projects = extractedDeps.getHead.projects.map(proj =>
          proj.copy(dependencies = proj.dependencies.filterNot(dep =>
            ignored.exists(mod => sameId(dep, mod))))))
        val added = all.inject.map(ModuleId.parse)
        val result = modDeps.copy(projects = modDeps.projects.map(proj =>
          proj.copy(dependencies = (proj.dependencies.++(added.map { d =>
            ProjectRef(d.getName, d.getOrganisation)
          }).distinct))))
        val newProjInfo = result +: extractedDeps.projInfo.tail
        extractedDeps.copy(projInfo = newProjInfo)
    }
  }

  /** Given an initial build configuration, extract *ALL* information needed for a full build. */
  def extract(tdir: File, extractionConfig: ExtractionConfig, logger: logging.Logger, debug: Boolean): ExtractionOutcome = try {
    val build = extractionConfig.buildConfig
    distributed.repo.core.ProjectDirs.useProjectExtractionDirectory(extractionConfig, tdir) { dir =>
      updateTimeStamp(dir)
      // NB: while resolving projects:
      // extractor.resolver.resolve() only resolves the main URI,
      // extractor.dependencyExtractor.resolve() also resolves the nested ones, recursively
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = ExtractionConfig(dependencyExtractor.resolve(extractionConfig.buildConfig, dir, this, logger))
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

  private def cacheExtract(config: ExtractionConfig, extract: ExtractedBuildMeta, logger: logging.Logger): Unit =
    IO.withTemporaryFile("extract", config.uuid) { file =>
      IO.write(file, writeValue(extract))
      logger.debug("Putting extraction information into: " + repository)
      repository.put(makeExtractKey(config), file)
    }

  def cachedExtractOr(config: ExtractionConfig, logger: logging.Logger)(f: => ExtractionOutcome): ExtractionOutcome =
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
