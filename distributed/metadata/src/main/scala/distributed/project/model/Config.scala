package distributed
package project
package model

import config.{ConfigPrint,ConfigRead, ConfigObject}
import ConfigPrint.makeMember
import ConfigRead.readMember
import sbt.Types.:+:
import sbt.HNil

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class ProjectBuildConfig(name: String, 
    system: String, 
    uri: String, 
    extra: ConfigObject = ProjectBuildConfig.emptyConfigObject)
    
object ProjectBuildConfig {
  
  val emptyConfigObject = config.parseString("""{}""").resolve.root
  
  implicit object PrettyPrinter extends ConfigPrint[ProjectBuildConfig] {
    def apply(c: ProjectBuildConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("name", c.name)
      sb append ","
      sb append makeMember("system", c.system)
      sb append ","
      sb append makeMember("uri", c.uri)
      sb append ","
      sb append makeMember("extra", c.extra)
      sb append "}"
      sb.toString
    }
  }
  
  implicit object Configured extends ConfigRead[ProjectBuildConfig] {
    import config._
    val Members = (
        readMember[String]("name") :^:
        readMember[String]("system") :^:
        readMember[String]("uri") :^:
        readMember[ConfigObject]("extra")
    )
    def unapply(c: ConfigValue): Option[ProjectBuildConfig] = 
      (c withFallback defaultProject) match {
        case Members(name :+: system :+: uri :+: extra :+: HNil) =>
          Some(ProjectBuildConfig(name, system, uri, extra))
        case _ => None
      }
    val defaultProject: ConfigObject = 
      config.parseString("""{
        system = "sbt"
        extra = {}
      }""").resolve.root
  }
}

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig])
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
    val Members = readMember[Seq[ProjectBuildConfig]]("projects")
    def unapply(c: ConfigValue): Option[DistributedBuildConfig] = c match {
      case Members(list) => Some(DistributedBuildConfig(list))
      case _                 => None
    }
  }
}