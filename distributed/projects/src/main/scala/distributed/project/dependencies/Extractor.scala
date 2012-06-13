package distributed
package project
package dependencies

import sbt.IO
import java.io.File
import resolve.ProjectResolver
import model.{Build,BuildConfig}
import logging._

abstract class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor,
    logger: logging.Logger) {
  
  /** Given an initial build configuraiton, extract *ALL* information needed for a full build. */
  def extract(build: BuildConfig): Build = 
    local.ProjectDirs.useDirFor(build) { dir =>
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      val config = resolver.resolve(build, dir)
      logger.debug("Extracting Dependencies for: " + build.name)
      val deps = dependencyExtractor.extract(build, dir)
      Build(config,deps)
    }
}

/** Given intiial configuration, this will extract information to do a distributed build.
 * 
 * 
 * Note: This needs huge cleanup and speed fixing.  Right now it just does what the script did.
 * We should probably cache directories and other kinds of niceties.
 */
//object Extractor extends Extractor(ProjectResolver, BuildDependencyExtractor, ConsoleLogger())
