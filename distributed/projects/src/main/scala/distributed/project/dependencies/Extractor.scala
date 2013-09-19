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
import distributed.project.model.Utils.{writeValue,readValue}
import distributed.logging.Logger.prepareLogMsg
import org.apache.ivy.core.module.id.ModuleId
import distributed.project.model.ProjectRef


/** This is used to extract dependencies from projects. */
class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor,
    repository: Repository) {

  /**
   * Filter or modify the project dependencies, according to the specification
   * contained in DepsModifiers. It is presently used to ignore (and not rewire) certain
   * dependencies; that could be further extended in the future in order to
   * modify the project dependencies in other manners.
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
        extractedDeps.copy(projects = extractedDeps.projects.map(proj =>
          proj.copy(dependencies = proj.dependencies.filterNot(dep =>
            ignored.exists(mod => sameId(dep, mod))))))
    }
  }

  /** Given an initial build configuration, extract *ALL* information needed for a full build. */
  def extract(tdir: File, extractionConfig: ExtractionConfig, logger: logging.Logger): ExtractionOutcome = try {
    val build = extractionConfig.buildConfig
    distributed.repo.core.ProjectDirs.useProjectExtractionDirectory(extractionConfig, tdir) { dir =>
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = ExtractionConfig(resolver.resolve(build, dir, logger),extractionConfig.buildOptions)
      logger.debug("Repeatable Config: " + writeValue(config))
      // Here, we attempt to cache our extracted dependencies rather than do
      // resolution again.
      // TODO - This should be configurable!
      cachedExtractOr(config, logger) {
        logger.info("Extracting dependencies for: " + build.name)
        val extractedDeps = dependencyExtractor.extract(config, dir, logger)
        // process deps.ignore clauses
        val deps = modifiedDeps(config.buildConfig.deps, extractedDeps, logger)
        logger.debug("Dependencies = " + writeValue(deps))
        cacheExtract(config, deps, logger)
        ExtractionOK(build.name, Seq.empty, Seq(ProjectConfigAndExtracted(config.buildConfig, deps)))
      }
    }
  } catch {
    case e =>
      ExtractionFailed(extractionConfig.buildConfig.name, Seq.empty, prepareLogMsg(logger, e))
  }
  
    private def makeExtractKey(config: ExtractionConfig) = 
      "meta/extract/" + config.uuid
  
    private def cacheExtract(config: ExtractionConfig, extract: ExtractedBuildMeta, logger: logging.Logger): Unit =
      IO.withTemporaryFile("extract", config.uuid) { file =>
        IO.write(file, writeValue(extract))
        logger.debug("Putting extraction information into: " + repository)
        repository.put(makeExtractKey(config), file)
      }

    private def cachedExtractOr(config: ExtractionConfig, logger: logging.Logger)(f: => ExtractionOutcome): ExtractionOutcome = 
      try {
        val key = makeExtractKey(config)
    	val file = repository.get(key)
    	logger.debug("Dependencies are cached!")
    	val deps = readValue[ExtractedBuildMeta](file)
    	logger.debug("Dependencies = " + writeValue(deps))
    	if (deps.subproj.nonEmpty)
    	  logger.info(deps.subproj.mkString("The following subprojects will be built in project "+config.buildConfig.name+": ",", ",""))
    	ExtractionOK(config.buildConfig.name,Seq.empty,Seq(ProjectConfigAndExtracted(config.buildConfig, deps)))
      } catch {
        case _: Exception => f
      }
}
