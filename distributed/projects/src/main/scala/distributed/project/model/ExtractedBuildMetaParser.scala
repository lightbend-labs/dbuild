package distributed
package project
package model

import config._

/** A parser for things extracted from a build's meta configruation. */ 
object ExtractedBuildMetaParser {
  
  def parseMetaFile(f: java.io.File) = {
    val r = new java.io.FileReader(f)
    try parseMeta(r)
    finally r.close()
  }
  def parseMetaString(in: String) = parseBase(config.parseString(in).resolve.root)
  def parseMeta(in: java.io.Reader) = parseBase(config.parse(in).resolve.root)
  
  
  private def parseBase(c: ConfigValue): Option[ExtractedBuildMeta] =
    ExtractedBuildMeta.Configured.unapply(c)
}