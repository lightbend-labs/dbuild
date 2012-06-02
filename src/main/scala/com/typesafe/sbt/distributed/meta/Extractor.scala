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


