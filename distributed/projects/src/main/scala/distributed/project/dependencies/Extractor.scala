package distributed
package project
package dependencies

import sbt.IO
import sbt.Path._
import java.io.File

import project.resolve.ProjectResolver
import model.{RepeatableProjectBuild,ProjectBuildConfig}
import logging._
import config.makeConfigString


/** This is used to extract dependencies from projects. */
class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor) {
  
  /** Given an initial build configuraiton, extract *ALL* information needed for a full build. */
  def extract(tdir: File, build: ProjectBuildConfig, logger: logging.Logger): RepeatableProjectBuild = 
    local.ProjectDirs.useProjectExtractionDirectory(build, tdir) { dir =>
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = resolver.resolve(build, dir, logger)
      logger.debug("Repeatable Config: " + makeConfigString(config))
      logger.debug("Extracting Dependencies for: " + build.name)
      val deps = dependencyExtractor.extract(build, dir, logger)
      logger.debug("Dependencies = " + makeConfigString(deps))
      RepeatableProjectBuild(config,deps)
    }
}
