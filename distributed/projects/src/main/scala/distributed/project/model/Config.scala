package distributed
package project
package model

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class BuildConfig(name: String, 
    system: String, 
    uri: String, 
    directory: String)
    
object BuildConfig {
  import pretty._
  
  implicit object PrettyPrinter extends PrettyPrint[BuildConfig] {
    def apply(c: BuildConfig): String = {
      val sb = new StringBuffer("{\n")
      sb append ("  name = \"%s\"\n" format (c.name))
      sb append ("  system = \"%s\"\n" format (c.system))
      sb append ("  uri = \"%s\"\n" format (c.uri))
      sb append ("  directory = \"%s\"\n" format (c.directory))
      sb append "}"
      sb.toString
    }
  }
}

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[BuildConfig])
object DistributedBuildConfig {
  import pretty._
  implicit object PrettyPrinter extends PrettyPrint[DistributedBuildConfig] {
     def apply(build: DistributedBuildConfig): String = {
      val sb = new StringBuffer("{\n")
      sb append ("projects = %s\n" format (PrettyPrint(build.projects)))
      sb append "}"
      sb.toString
    }
  }
}