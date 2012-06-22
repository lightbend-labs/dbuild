package distributed
package project

import model._
import config._
import collection.JavaConverters._
import config.ConfigValueType



object BuildResultFileParser {
  
  def parseMetaFile(f: java.io.File) = {
    val r = new java.io.FileReader(f)
    try parseMeta(r)
    finally r.close()
  }
  def parseMetaString(in: String) = parseBase(config.parseString(in).resolve.root)
  def parseMeta(in: java.io.Reader) = parseBase(config.parse(in).resolve.root)
  
  
  private def parseBase(c: ConfigObject): Option[BuildArtifacts] = {
    for {
      artifacts <- parseArtifacts(c get "artifacts")
      localRepo <- parseRepo(c get "localRepo")
    } yield BuildArtifacts(artifacts, localRepo)
  }
  
  def parseRepo(c: ConfigValue): Option[java.io.File] = c match {
    case ConfigString(file) => Some(new java.io.File(file))
    case _ => None
  }
  
  def parseArtifacts(c: ConfigValue): Option[Seq[ArtifactLocation]] = c match {
      case ConfigList(configs) =>
        Some(configs collect {          
          case c: ConfigObject => parseArtifact(c) 
        } flatten)
      case _ => None
  }
  // TODO - Auto case-class extraction?
  // TODO - Use validation?
  private def parseArtifact(c: ConfigObject): Option[ArtifactLocation] = {
    (c get "location", c get "info", c get "version") match {
      case (ConfigString(file), ParsedProjectDep(dep), ConfigString(version)) => 
        Some(ArtifactLocation(dep, new java.io.File(file), version))
      case _ => None
    }
  }
  
  // TODO - This is in two places now....
  object ParsedProjectDep {
    def unapply(c: ConfigObject): Option[ProjectDep] = 
      (c get "name", c get "organization") match {
        case (ConfigString(name), ConfigString(org)) => Some(ProjectDep(name,org))
        case _ => None
      }
  }
 
}