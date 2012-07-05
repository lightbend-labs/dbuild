package distributed
package project
package model

import config._
import collection.JavaConverters._
import config.ConfigValueType


/** A parser for BuildArtifacts serialized configurations. */
object BuildArtifactsParser {
  def parseMetaFile(f: java.io.File) = {
    val r = new java.io.FileReader(f)
    try parseMeta(r)
    finally r.close()
  }
  def parseMetaString(in: String) = parseBase(config.parseString(in).resolve.root)
  def parseMeta(in: java.io.Reader) = parseBase(config.parse(in).resolve.root)
  
  private def parseBase(c: ConfigObject): Option[BuildArtifacts] = 
    BuildArtifacts.Configured.unapply(c)
}