package distributed
package project
package dependencies

import sbt.IO
import sbt.Path._
import java.io.File

import project.resolve.ProjectResolver
import model.{ProjectConfigAndExtracted,ProjectBuildConfig,ExtractedBuildMeta}
import logging._
import repo.core.Repository
import distributed.project.model.Utils.fromHOCON
import distributed.project.model.Utils.mapper.{writeValueAsString,readValue}


/** This is used to extract dependencies from projects. */
class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor,
    repository: Repository) {
  
  /** Given an initial build configuraiton, extract *ALL* information needed for a full build. */
  def extract(tdir: File, build: ProjectBuildConfig, logger: logging.Logger): ProjectConfigAndExtracted = 
    local.ProjectDirs.useProjectExtractionDirectory(build, tdir) { dir =>
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = resolver.resolve(build, dir, logger)
      logger.debug("Repeatable Config: " + writeValueAsString(config))
      // Here, we attempt to cache our extracted dependencies rather than do
      // resolution again.
      // TODO - This should be configurable!
      cachedExtractOr(config, logger) {
        logger.debug("Extracting Dependencies for: " + build.name)
        val deps = dependencyExtractor.extract(build, dir, logger)      
        logger.debug("Dependencies = " + writeValueAsString(deps))
        cacheExtract(config, deps)
        ProjectConfigAndExtracted(config,deps)
      }
    }
  
    private def makeExtractKey(config: ProjectBuildConfig) = 
      "meta/extract/" + config.uuid
  
    private def cacheExtract(config: ProjectBuildConfig, extract: ExtractedBuildMeta): Unit =
      IO.withTemporaryFile("extract", config.uuid) { file =>
        IO.write(file, writeValueAsString(extract))
        println("Putting extraction information into: " + repository)
        repository.put(makeExtractKey(config), file)
      }
    
    private def cachedExtractOr(config: ProjectBuildConfig, logger: logging.Logger)(f: => ProjectConfigAndExtracted): ProjectConfigAndExtracted = 
      try {
        val key = makeExtractKey(config)
    	val file = repository.get(key)
    	logger.debug("Dependencies are cached!")
    	val deps = readValue[ExtractedBuildMeta](fromHOCON(file))
    	logger.debug("Dependencies = " + writeValueAsString(deps))
    	ProjectConfigAndExtracted(config, deps)
      } catch {
        case _: Exception => f
      }
}
