package distributed
package project
package model

import config._
import collection.JavaConverters._
import config.ConfigValueType



object ExtractedBuildMetaParser {
  
  def parseMetaFile(f: java.io.File) = {
    val r = new java.io.FileReader(f)
    try parseMeta(r)
    finally r.close()
  }
  def parseMetaString(in: String) = parseBase(config.parseString(in).resolve.root)
  def parseMeta(in: java.io.Reader) = parseBase(config.parse(in).resolve.root)
  
  
  private def parseBase(c: ConfigObject): Option[ExtractedBuildMeta] = {
    for {
      uri   <- ConfigString.unapply(c get "scm")
      projs <- parseProjects(c get "projects")
    } yield ExtractedBuildMeta(uri, projs)
  }
  
  def parseProjects(c: ConfigValue): Option[Seq[Project]] = c match {
      case ConfigList(projects) =>
        Some(projects collect {          
          case c: ConfigObject => parseProject(c) 
        } flatten)
      case _ => None
  }
  // TODO - Auto case-class extraction?
  // TODO - Use validation?
  private def parseProject(c: ConfigObject): Option[Project] = {
    (c get "name", c get "organization", c get "artifacts", c get "dependencies") match {
      case (ConfigString(name), ConfigString(org), ConfigList(arts), ConfigList(deps)) =>
        val dependencies = deps flatMap parseDep
        val artifacts = arts flatMap parseDep
        Some(Project(name, org, artifacts, dependencies))
      case _ => None
    }
  }
  
  private def parseDep(c: ConfigValue): Option[ProjectDep] = c match {
    // TODO - Handle optional classifier...
    case c: ConfigObject =>
      (c get "name", c get "organization", c get "ext", c get "classifier") match {
        case (ConfigString(name), ConfigString(org), ConfigString(ext), ConfigString(classifier)) =>
          Some(ProjectDep(name, org, ext, Some(classifier)))
        case (ConfigString(name), ConfigString(org), ConfigString(ext), _) => 
          Some(ProjectDep(name, org, ext))
        case _ => None
      }
    case _ => None
  }
 
}