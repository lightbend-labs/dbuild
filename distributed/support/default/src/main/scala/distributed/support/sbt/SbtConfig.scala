package distributed
package support
package sbt

import config.{ConfigRead, ConfigPrint}

/** Configuration used for SBT builds. */
case class SbtConfig(
    sbtVersion: String,
    directory: String,
    projects: Seq[String] = Seq.empty)


// TODO - Autogenerate SBT versions!
object SbtConfig {
  def sbtVersion = "0.12.0-RC3"
    
  implicit object Configured extends ConfigRead[SbtConfig] with ConfigPrint[SbtConfig] {
    import config._
    import ConfigPrint.makeMember
    import ConfigRead.readMember
    import _root_.sbt.Types.:+:
    import _root_.sbt.HNil
    private val defaultObj: ConfigObject =
      config.parseString("""{
        sbt-version = "%s"
        directory = ""
        projects = []
      }""" format (sbtVersion)).resolve.root
    private val Members = (
        readMember[String]("sbt-version") :^:
        readMember[String]("directory") :^:
        readMember[Seq[String]]("projects")
    )
    def apply(c: SbtConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("sbt-version", c.sbtVersion)
      sb append ","
      sb append makeMember("directory", c.directory)
      sb append ","
      sb append makeMember("projects", c.projects)
      sb append "}"
      sb.toString
    }
    def unapply(c: ConfigValue): Option[SbtConfig] = 
      (c withFallback defaultObj) match {
        case Members(sbtV :+: dir :+: projs :+: HNil) =>
          Some(SbtConfig(sbtV, dir, projs))
        case _ => None
      }
  } 
}