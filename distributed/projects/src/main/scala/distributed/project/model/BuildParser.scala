package distributed
package project
package model

import config._
import com.typesafe.config.ConfigObject

object DistributedBuildParser {
  def parseBuildFile(f: java.io.File): DistributedBuildConfig = {
    val r = new java.io.FileReader(f)
    try parseBuild(r)
    finally r.close
  }
  
  def parseBuildString(in: String): DistributedBuildConfig = {
    val r = new java.io.StringReader(in)
    try parseBuild(r)
    finally r.close
  }
  
  def parseBuild(in: java.io.Reader): DistributedBuildConfig = {
    val projectList = config.parse(in).resolve.getList("projects")
    DistributedBuildConfig(parseProjects(projectList))
  }
  
  def parseProjects(c: ConfigList): Seq[BuildConfig] = {
    import collection.JavaConverters._
    for {
      t <- c.iterator.asScala.toSeq
      if t.isInstanceOf[ConfigObject]
      build <- parseProject(t.asInstanceOf[ConfigObject])
    } yield build
  }
    
  def parseProject(c: ConfigObject): Option[BuildConfig] = {
    val p = c.withFallback(defaultProject)
    (p get "name", p get "system", p get "uri", p get "directory") match {
      case (ConfigString(n), ConfigString(s), ConfigString(u), ConfigString(d)) =>
        Some(BuildConfig(n,s,u,d))
      case _ => None
    }
  }
  
  lazy val defaultProject: ConfigObject = 
    config.parseString("""{
          system = "sbt"
          directory = ""
        }""").resolve.root
}