package distributed
package project
package model

import config.{ConfigPrint,ConfigRead}
import ConfigPrint.makeMember
import ConfigRead.readMember
import graph.Graphs
import sbt.Types.:+:
import sbt.HNil

/** Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.
 */
case class RepeatableProjectBuild(config: ProjectBuildConfig, extracted: ExtractedBuildMeta)
object RepeatableProjectBuild {
  
  
  implicit object PrettyPrinter extends ConfigPrint[RepeatableProjectBuild] {
    def apply(build: RepeatableProjectBuild): String = {
      import build._
      val sb = new StringBuffer("{")
      sb append makeMember("config", config)
      sb append ","
      sb append makeMember("extracted", extracted)
      sb append "}"
      sb.toString
    }    
  } 
  
  implicit object Configured extends ConfigRead[RepeatableProjectBuild] {
    import config._
    val Members = (
        readMember[ProjectBuildConfig]("config") :^:
        readMember[ExtractedBuildMeta]("extracted")
    )
    def unapply(c: ConfigValue): Option[RepeatableProjectBuild] = 
      c match {
        case Members(config :+: extracted :+: HNil) =>
          Some(RepeatableProjectBuild(config, extracted))
        case _ => None
      }
  }
}

/** Metadata for a project, used when generating unique SHA. */
case class ProjectMeta(config: ProjectBuildConfig,
                       dependencies: Seq[ProjectMeta])

/** A distributed build containing projects in *build order*
 *  Also known as the repeatable config. 
 */
case class RepeatableDistributedBuild(builds: Seq[RepeatableProjectBuild]) {
  def config = DistributedBuildConfig(builds map (_.config))
  
  /** Our own graph helper for interacting with the build meta information. */
  private[this] lazy val graph = new BuildGraph(builds)
  /** All of our builds in order. */
  lazy val orderedBuilds = (Graphs safeTopological graph map (_.value)).reverse
  
  /** A map from project name -> unique project metadata. */
  lazy val projectMeta: Map[String, ProjectMeta] = {
    def makeMeta(remaining: Seq[RepeatableProjectBuild], current: Map[String, ProjectMeta]): Map[String,ProjectMeta] =
      if(remaining.isEmpty) current
      else {
        // Pull out current repeatable config for a project.
        val head = remaining.head
        val node = graph.nodeFor(head) getOrElse sys.error("O NOES -- TODO better grpah related puke message")
        val subgraph = Graphs.subGraphFrom(graph)(node) map (_.value)
        val dependencies = 
          for {
            dep <- (subgraph - head)
          } yield current get dep.config.name getOrElse sys.error("ISSUE! Build has circular dependencies.")
        val sortedDeps = dependencies.toSeq.sortBy (_.config.name)
        val headMeta = ProjectMeta(head.config, sortedDeps)
        makeMeta(remaining.tail, current + (headMeta.config.name -> headMeta))
      }
    makeMeta(orderedBuilds, Map.empty)
  }
  /** Machine readable UUID for a project. */
  def projectUUID(name: String): Option[String] = 
    projectMeta get name map hashing.sha1Sum
    
  /** The unique SHA for this build. */
  def uuid: String = ""
  
}
object RepeatableDistributedBuild {
  
  implicit object DistributedBuildPretty extends ConfigPrint[RepeatableDistributedBuild] {
    def apply(build: RepeatableDistributedBuild): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("projects", build.builds)
      sb append "}"
      sb.toString
    }
  }
  
  implicit object Configured extends ConfigRead[RepeatableDistributedBuild] {
    import config._
    val Members = (
        readMember[Seq[RepeatableProjectBuild]]("projects")
    )
    def unapply(c: ConfigValue): Option[RepeatableDistributedBuild] = 
      c match {
        case Members(projects) =>
          Some(RepeatableDistributedBuild(projects))
        case _ => None
      }
  }
}
