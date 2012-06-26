package distributed
package project
package model

import config.ConfigPrint
import ConfigPrint.makeMember

/** A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectDep(
    name: String, 
    organization: String, 
    extension: String = "jar", 
    classifier: Option[String] = None)
object ProjectDep {
  implicit object ProjectDepPrettyPrint extends ConfigPrint[ProjectDep] {
    def apply(t: ProjectDep): String = {
      import t._
      val sb = new StringBuilder("{")
      sb append makeMember("name", name)
      sb append ","
      sb append makeMember("organization", organization)
      sb append ","
      sb append makeMember("ext", extension)
      classifier foreach { o => 
        sb append ","
        sb append makeMember("classifier", o)
      }
      sb append ("}")
      sb.toString
    }   
  }  
}

/** Represents extracted Project information in a build.  A project is akin to a
 * deployed artifact for a given build, and may have dependencies.
 */
case class Project(
    name: String,
    organization: String,
    artifacts: Seq[ProjectDep],
    dependencies: Seq[ProjectDep])
object Project {
  implicit object ProjectPrettyPrint extends ConfigPrint[Project] {
    def apply(t: Project): String = {
      import t._
      val sb = new StringBuilder("{")
      sb append  makeMember("name", name)
      sb append ","
      sb append makeMember("organization", organization)
      sb append ","
      sb append makeMember("artifacts", artifacts)
      sb append ","
      sb append makeMember("dependencies", dependencies)
      sb append ("}")
      sb.toString
    }    
  }
}

/** Represents the *Extracted* metadata of a build.
 */
case class ExtractedBuildMeta(uri: String, projects: Seq[Project]) {
  override def toString = "Build(%s, %s)" format (uri, projects.mkString("\n\t", "\n\t", "\n"))
}
object ExtractedBuildMeta {
  implicit object  BuildPretty extends ConfigPrint[ExtractedBuildMeta] {
    def apply(b: ExtractedBuildMeta): String = {
      import b._
      val sb = new StringBuilder("{")
      sb append makeMember("scm", uri)
      sb append ","
      sb append makeMember("projects", projects)
      sb append ("}")
      sb.toString
    }
  }
}


