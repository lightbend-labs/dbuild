package distributed
package project
package dependencies

import model._
import build.LocalBuildRunner

/** Interface for extracting project metadata. */
trait BuildDependencyExtractor {
  /**
   * Extract project metadata from a local
   * project. Project MUST have already been
   * resolved, via a call to resolve()
   */
  def extract(config: ExtractionConfig, dir: java.io.File, extractor:Extractor, log: logging.Logger, debug: Boolean): ExtractedBuildMeta

  /** Returns true or false, depending on whether or not this extractor can handle
   * a given build system.
   */
  def canHandle(system: String): Boolean

  /**
   * For those build systems that support nested projects, this routine will
   * also recursively resolve all nested projects
   */
  def resolve(config: ProjectBuildConfig, dir: java.io.File, extractor:Extractor, log: logging.Logger): ProjectBuildConfig
}

class MultiBuildDependencyExtractor(buildSystems: Seq[BuildSystem[Extractor, LocalBuildRunner]]) extends BuildDependencyExtractor {
  def canHandle(system: String): Boolean = buildSystems exists (_.name == system)
  private def findSystem(config: ProjectBuildConfig) = {
    buildSystems find (_.name == config.system) match {
      case Some(r) => r
      case _       => sys.error("No extractor found for: " + config.name)
    }
  }
  def extract(config: ExtractionConfig, dir: java.io.File, extractor:Extractor, log: logging.Logger, debug: Boolean): ExtractedBuildMeta =
    findSystem(config.buildConfig).extractDependencies(config, dir, extractor, log, debug)
  def resolve(config: ProjectBuildConfig, dir: java.io.File, extractor:Extractor, log: logging.Logger): ProjectBuildConfig = {
    if (!dir.isDirectory()) sys.error("Internal error, please report; resolve() dir does not exist: "+dir.getCanonicalPath())
    findSystem(config).resolve(config, dir, extractor, log)
  }
}
