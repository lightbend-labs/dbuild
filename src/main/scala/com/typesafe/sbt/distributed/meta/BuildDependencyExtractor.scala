package com.typesafe.sbt.distributed
package meta


/** Interface for extracting project metadata. */
trait BuildDependencyExtractor {
  /** Extract project metadata from a local
   * project.
   */
  def extract(config: BuildConfig, dir: java.io.File): ExtractedBuildMeta
  /** Returns true or false, depending on whether or not this extractor can handle
   * a given build system.
   */
  def canHandle(system: String): Boolean
}

// TODO - Plugable?
object BuildDependencyExtractor {
  private[this] val extractors = Seq(new support.sbt.SbtDependencyExtractor)
  def canHandle(system: String): Boolean = extractors exists (_ canHandle system)
  def extract(config: BuildConfig, dir: java.io.File): ExtractedBuildMeta =
    (extractors 
      find (_ canHandle config.system) 
      map (_.extract(config,dir)) 
      getOrElse sys.error("No extractor found for: " + config))
}


