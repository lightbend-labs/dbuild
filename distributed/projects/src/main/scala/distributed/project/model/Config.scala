package distributed
package project
package model

import config.ConfigPrint
import ConfigPrint.makeMember
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
}