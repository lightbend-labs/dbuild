package distributed
package project
package dependencies

import model._


/** Interface for extracting project metadata. */
trait BuildDependencyExtractor {
  /** Extract project metadata from a local
   * project.
   */
  def extract(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ExtractedBuildMeta
  /** Returns true or false, depending on whether or not this extractor can handle
   * a given build system.
   */
  def canHandle(system: String): Boolean
}

class MultiBuildDependencyExtractor(buildSystems: Seq[BuildSystem]) extends BuildDependencyExtractor {
  def canHandle(system: String): Boolean = buildSystems exists (_.name == system)
  def extract(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ExtractedBuildMeta =
    (buildSystems 
      find (_.name == config.system) 
      map (_.extractDependencies(config, dir, log)) 
      getOrElse sys.error("No extractor found for: " + config))
}


