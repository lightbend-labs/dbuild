package distributed
package support
package sbt

import project.model._
import config.{ConfigRead, ConfigPrint}

/** Configuration used for SBT builds. */
case class SbtConfig(
    sbtVersion: String,
    directory: String,
    measurePerformance: Boolean = false,
    options: Seq[String] = Seq.empty,
    projects: Seq[String] = Seq.empty)


// TODO - Autogenerate SBT versions!
object SbtConfig {
  def sbtVersion = Defaults.sbtVersion
    
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
        measure-performance = "false"
        options = []
        projects = []
      }""" format (sbtVersion)).resolve.root
    private val Members = (
        readMember[String]("sbt-version") :^:
        readMember[String]("directory") :^:
        readMember[Boolean]("measure-performance") :^:
        readMember[Seq[String]]("options") :^:
        readMember[Seq[String]]("projects")
    )
    def apply(c: SbtConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("sbt-version", c.sbtVersion)
      sb append ","
      sb append makeMember("directory", c.directory)
      sb append ","
      sb append makeMember("measure-performance", c.measurePerformance)
      sb append ","
      sb append makeMember("options", c.options)
      sb append ","
      sb append makeMember("projects", c.projects)
      sb append "}"
      sb.toString
    }
    def unapply(c: ConfigValue): Option[SbtConfig] =
      (c withFallback defaultObj) match {
        case Members(sbtV :+: dir :+: perf :+: opts :+: projs :+: HNil) =>
          Some(SbtConfig(sbtV, dir, perf, opts, projs))
        case _ => None
      }
  }
}


case class SbtBuildConfig(config: SbtConfig, artifacts: BuildArtifacts)
object SbtBuildConfig {
  implicit object Configured extends ConfigPrint[SbtBuildConfig] with ConfigRead[SbtBuildConfig] {
    import config._
    import ConfigPrint.makeMember
    import ConfigRead.readMember
    import _root_.sbt.Types.:+:
    import _root_.sbt.HNil
    private val Members = (
      readMember[SbtConfig]("config") :^:
      readMember[BuildArtifacts]("artifacts")
    )
    def apply(c: SbtBuildConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("config", c.config)
      sb append ","
      sb append makeMember("artifacts", c.artifacts)
      sb append "}"
      sb.toString
    }
    def unapply(c: ConfigValue): Option[SbtBuildConfig] = 
      c match {
        case Members(config :+: artifacts :+: HNil) =>
          Some(SbtBuildConfig(config, artifacts))
        case _ => None
      }    
  }
}