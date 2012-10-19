package distributed
package project
package model

import config.{ConfigPrint,ConfigRead}
import ConfigPrint.makeMember
import ConfigRead.readMember
import sbt.Types.:+:
import sbt.HNil

/** A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectRef(
    name: String, 
    organization: String, 
    extension: String = "jar", 
    classifier: Option[String] = None) {
  override def toString = organization + ":" + name + ":" + (classifier map (_ + ":") getOrElse "") + extension
}
object ProjectRef {
  implicit object ProjectDepPrettyPrint extends ConfigPrint[ProjectRef] {
    def apply(t: ProjectRef): String = {
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
  
  implicit object Configured extends ConfigRead[ProjectRef] {
    import config._
    val Members = (
      readMember[String]("name") :^:
      readMember[String]("organization") :^:
      readMember[String]("ext") :^:
      readMember[Option[String]]("classifier")
    )
    
    def unapply(c: ConfigValue): Option[ProjectRef] = c match {
      case Members(name :+: org :+: ext :+: classifier :+: HNil) =>
        Some(ProjectRef(name,org,ext,classifier))
      // TODO - Handle optional classifier...
      case _ => None
    }
  }
}

/** Represents extracted Project information in a build.  A project is akin to a
 * deployed artifact for a given build, and may have dependencies.
 */
case class Project(
    name: String,
    organization: String,
    artifacts: Seq[ProjectRef],
    dependencies: Seq[ProjectRef])
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
  implicit object Configured extends ConfigRead[Project] {
    import config._
    val Members = (
      readMember[String]("name") :^:
      readMember[String]("organization") :^:
      readMember[Seq[ProjectRef]]("artifacts") :^:
      readMember[Seq[ProjectRef]]("dependencies")
    )
    val ProjectRefList = implicitly[ConfigRead[Seq[ProjectRef]]]
    def unapply(c: ConfigValue): Option[Project] = c match {
      case Members(name :+: org :+: artifacts :+: deps :+: HNil) =>
        Some(Project(name, org, artifacts, deps))
      case _ => None
    }
  }
}

/** Represents the *Extracted* metadata of a build.
 * 
 * This includes things like dependencies.   Actually nothing else currently.
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
  implicit object Configured extends ConfigRead[ExtractedBuildMeta] {
    import config._
     val Members = (
      readMember[String]("scm") :^:
      readMember[Seq[Project]]("projects")
    )
    val ProjectList = implicitly[ConfigRead[Seq[Project]]]
    def unapply(c: ConfigValue): Option[ExtractedBuildMeta] = c match {
      case Members(scm :+: projects :+: HNil) => 
        Some(ExtractedBuildMeta(scm, projects))
      case _ => None
    }
  }
}


