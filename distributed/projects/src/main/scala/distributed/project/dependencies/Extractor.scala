package distributed
package project
package dependencies

import sbt.IO
import sbt.Path._
import java.io.File
import project.resolve.ProjectResolver
import model.{ProjectConfigAndExtracted,ProjectBuildConfig,ExtractedBuildMeta,ExtractionOK,ExtractionOutcome,ExtractionFailed,ExtractionConfig}
import logging._
import repo.core.Repository
import distributed.project.model.Utils.{writeValue,readValue}
import distributed.logging.Logger.prepareLogMsg


/** This is used to extract dependencies from projects. */
class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor,
    repository: Repository) {

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
        val deps = dependencyExtractor.extract(config, dir, logger)
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
