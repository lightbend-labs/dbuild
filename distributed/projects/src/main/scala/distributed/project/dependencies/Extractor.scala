package distributed
package project
package dependencies

import sbt.IO
import java.io.File
import resolve.ProjectResolver
import model.{Build,BuildConfig}
import logging._


/** This is used to extract dependencies from projects. */
class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor) {
  
  /** Given an initial build configuraiton, extract *ALL* information needed for a full build. */
  def extract(build: BuildConfig, logger: logging.Logger): Build = 
    local.ProjectDirs.useDirFor(build) { dir =>
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = resolver.resolve(build, dir, logger)
      logger.debug("Extracting Dependencies for: " + build.name)
      val deps = dependencyExtractor.extract(build, dir, logger)
      Build(config,deps)
    }
}
