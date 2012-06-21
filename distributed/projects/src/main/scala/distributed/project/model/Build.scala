package distributed
package project
package model

import pretty._
import ConfigPrint.makeMember
/** Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.
 */
case class Build(config: BuildConfig, extracted: ExtractedBuildMeta)
object Build {
  
  
  implicit object PrettyPrinter extends ConfigPrint[Build] {
    def apply(build: Build): String = {
      import build._
      val sb = new StringBuffer("{")
      sb append makeMember("config", config)
      sb append ","
      sb append makeMember("extracted", extracted)
      sb append "}"
      sb.toString
    }    
  } 
}


/** A distributed build containing projects in *build order*/
case class DistributedBuild(builds: Seq[Build])
object DistributedBuild {
  implicit object DistributedBuildPretty extends ConfigPrint[DistributedBuild] {
    def apply(build: DistributedBuild): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("projects", build.builds)
      sb append "}"
      sb.toString
    }
  }
}
