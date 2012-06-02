package com.typesafe.sbt.distributed
package meta


/** Interface for extracting project metadata. */
trait Extractor {
  /** Extract project metadata from a location URI. */
  def extract(uri: String): Build
  /** Returns true or false, depending on whether or not this extractor can handle
   * a given build system.
   */
  def canHandle(system: String): Boolean
}

// TODO - Plugable?
object Extractor {
  private[this] val extractors = Seq(new support.sbt.SbtExtractor)
  def canHandle(system: String): Boolean = extractors exists (_ canHandle system)
  def extract(system: String, uri: String): Build =
    (extractors 
      find (_ canHandle system) 
      map (_ extract uri) 
      getOrElse sys.error("No extractor found for: " + uri))
}


