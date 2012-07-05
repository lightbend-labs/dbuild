package distributed
package project
package model

import config.{ConfigPrint,ConfigRead}
import ConfigPrint.makeMember
import ConfigRead.readMember
import sbt.Types.:+:
import sbt.HNil

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class BuildConfig(name: String, 
    system: String, 
    uri: String, 
    directory: String)
    
object BuildConfig {
  
  implicit object PrettyPrinter extends ConfigPrint[BuildConfig] {
    def apply(c: BuildConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("name", c.name)
      sb append ","
      sb append makeMember("system", c.system)
      sb append ","
      sb append makeMember("uri", c.uri)
      sb append ","
      sb append makeMember("directory", c.directory)
      sb append "}"
      sb.toString
    }
  }
  
  implicit object Configured extends ConfigRead[BuildConfig] {
    import config._
    val Members = (
        readMember[String]("name") :^:
        readMember[String]("system") :^:
        readMember[String]("uri") :^:
        readMember[String]("directory")
    )
    def unapply(c: ConfigValue): Option[BuildConfig] = 
      (c withFallback defaultProject) match {
        case Members(name :+: system :+: uri :+: directory :+: HNil) =>
          Some(BuildConfig(name,system,uri,directory))
        case _ => None
      }
    val defaultProject: ConfigObject = 
      config.parseString("""{
        system = "sbt"
        directory = ""
      }""").resolve.root
  }
}

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[BuildConfig])
object DistributedBuildConfig {
  implicit object PrettyPrinter extends ConfigPrint[DistributedBuildConfig] {
     def apply(build: DistributedBuildConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("projects", build.projects)
      sb append "}"
      sb.toString
    }
  }
  implicit object Configured extends ConfigRead[DistributedBuildConfig] {
    import config._
    val Members = readMember[Seq[BuildConfig]]("projects")
    def unapply(c: ConfigValue): Option[DistributedBuildConfig] = c match {
      case Members(list) => Some(DistributedBuildConfig(list))
      case _                 => None
    }
  }
}