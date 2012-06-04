package com.typesafe.sbt.distributed
package meta


/** Interface for extracting project metadata. */
trait Extractor {
  /** Extract project metadata from a location URI. */
  def extract(config: BuildConfig): ExtractedBuildMeta
  /** Returns true or false, depending on whether or not this extractor can handle
   * a given build system.
   */
  def canHandle(system: String): Boolean
}

// TODO - Plugable?
object Extractor {
  private[this] val extractors = Seq(new support.sbt.SbtExtractor)
  def canHandle(system: String): Boolean = extractors exists (_ canHandle system)
  def extract(config: BuildConfig): ExtractedBuildMeta =
    (extractors 
      find (_ canHandle config.system) 
      map (_ extract config) 
      getOrElse sys.error("No extractor found for: " + config))
}


