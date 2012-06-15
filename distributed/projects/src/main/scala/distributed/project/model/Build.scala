package distributed
package project
package model

/** Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.
 */
case class Build(config: BuildConfig, extracted: ExtractedBuildMeta)
object Build {
  import pretty._
  implicit object PrettyPrinter extends PrettyPrint[Build] {
    def apply(build: Build): String = {
      val sb = new StringBuffer("{\n")
      sb append ("config = %s\n" format (PrettyPrint(build.config)))
      sb append ("extracted = %s\n" format (PrettyPrint(build.extracted)))
      sb append "}"
      sb.toString
    }    
  } 
}


/** A distributed build containing projects in *build order*/
case class DistributedBuild(builds: Seq[Build])
object DistributedBuild {
  import pretty._
  implicit object DistributedBuildPretty extends PrettyPrint[DistributedBuild] {
    def apply(build: DistributedBuild): String = {
      val sb = new StringBuffer("{\n")
      sb append ("projects = %s\n" format (PrettyPrint(build.builds)))
      sb append "}"
      sb.toString
    }
  }
}
