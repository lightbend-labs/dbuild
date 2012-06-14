package distributed
package project
package model

import pretty._

/** A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectDep(name: String, organization: String)
object ProjectDep {
  implicit object ProjectDepPrettyPrint extends PrettyPrint[ProjectDep] {
    def apply(t: ProjectDep): String = {
      import t._
      val sb = new StringBuilder("  {\n")
      sb append ("    name = %s\n" format (name))
      sb append ("    organization = %s\n" format (organization))
      sb append ("  }\n")
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
    dependencies: Seq[ProjectDep])
object Project {
  implicit object ProjectPrettyPrint extends PrettyPrint[Project] {
    def apply(t: Project): String = {
      import t._
      val sb = new StringBuilder("{\n")
      sb append ("  name = %s\n" format (name))
      sb append ("  organization = %s\n" format (organization))
      sb append ("  dependencies = %s\n" format (PrettyPrint(dependencies)))
      sb append ("}")
      sb.toString
    }    
  }
}

/** Represents the *Extracted* metadata of a build.
 */
case class ExtractedBuildMeta(uri: String, projects: Seq[Project]) {
  override def toString = "Build(%s)" format (uri)
}
object ExtractedBuildMeta {
  implicit object  BuildPretty extends PrettyPrint[ExtractedBuildMeta] {
    def apply(b: ExtractedBuildMeta): String = {
      import b._
      val sb = new StringBuilder("{\n")
      sb append ("scm      = \"%s\"\n" format(uri))
      sb append ("projects = %s\n" format (PrettyPrint(projects)))
      sb append ("}")
      sb.toString
    }
  }
}


