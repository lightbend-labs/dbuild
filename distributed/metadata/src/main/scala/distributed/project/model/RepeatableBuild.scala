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
case class ProjectConfigAndExtracted(config: ProjectBuildConfig, extracted: ExtractedBuildMeta)
object ProjectConfigAndExtracted {
  
  
  implicit object PrettyPrinter extends ConfigPrint[ProjectConfigAndExtracted] {
    def apply(build: ProjectConfigAndExtracted): String = {
      import build._
      val sb = new StringBuffer("{")
      sb append makeMember("config", config)
      sb append ","
      sb append makeMember("extracted", extracted)
      sb append "}"
      sb.toString
    }    
  } 
  
  implicit object Configured extends ConfigRead[ProjectConfigAndExtracted] {
    import config._
    val Members = (
        readMember[ProjectBuildConfig]("config") :^:
        readMember[ExtractedBuildMeta]("extracted")
    )
    def unapply(c: ConfigValue): Option[ProjectConfigAndExtracted] = 
      c match {
        case Members(config :+: extracted :+: HNil) =>
          Some(ProjectConfigAndExtracted(config, extracted))
        case _ => None
      }
  }
}

/** Metadata for a project, used when generating unique SHA. */
case class RepeatableProjectBuild(config: ProjectBuildConfig,
                       dependencies: Seq[RepeatableProjectBuild]) {
  /** UUID for this project. */
  def uuid = hashing sha1 this
}
object RepeatableProjectBuild {
  implicit object Configured extends ConfigRead[RepeatableProjectBuild] with ConfigPrint[RepeatableProjectBuild] {
    def apply(build: RepeatableProjectBuild): String = {
      import build._
      val sb = new StringBuffer("{")
      sb append makeMember("config", config)
      sb append ","
      sb append makeMember("dependencies", build.dependencies)
      sb append "}"
      sb.toString
    }    
    import config._
    val Members = (
        readMember[ProjectBuildConfig]("config") :^:
        readMember[Seq[RepeatableProjectBuild]]("dependencies")
    )
    def unapply(c: ConfigValue): Option[RepeatableProjectBuild] = 
      c match {
        case Members(config :+: dependencies :+: HNil) =>
          Some(RepeatableProjectBuild(config, dependencies))
        case _ => None
      }
  }
}

/** A distributed build containing projects in *build order*
 *  Also known as the repeatable config. 
 */
case class RepeatableDistributedBuild(builds: Seq[ProjectConfigAndExtracted]) {
  def repeatableBuildConfig = DistributedBuildConfig(builds map (_.config))
  def repeatableBuildString = _root_.config.ConfigPrint(repeatableBuildConfig)
  
  /** Our own graph helper for interacting with the build meta information. */
  private[this] lazy val graph = new BuildGraph(builds)
  /** All of our repeatable build configuration in build order. */
  lazy val repeatableBuilds: Seq[RepeatableProjectBuild] = {
    def makeMeta(remaining: Seq[ProjectConfigAndExtracted], 
                 current: Map[String, RepeatableProjectBuild],
                 ordered: Seq[RepeatableProjectBuild]): Seq[RepeatableProjectBuild] =
      if(remaining.isEmpty) ordered
      else {
        // Pull out current repeatable config for a project.
        val head = remaining.head
        val node = graph.nodeFor(head) getOrElse sys.error("O NOES -- TODO better graph related puke message")
        val subgraph = Graphs.subGraphFrom(graph)(node) map (_.value)
        val dependencies = 
          for {
            dep <- (subgraph - head)
          } yield current get dep.config.name getOrElse sys.error("ISSUE! Build has circular dependencies.")
        val sortedDeps = dependencies.toSeq.sortBy (_.config.name)
        val headMeta = RepeatableProjectBuild(head.config, sortedDeps)
        makeMeta(remaining.tail, current + (headMeta.config.name -> headMeta), ordered :+ headMeta)
      }
    val orderedBuilds = (Graphs safeTopological graph map (_.value)).reverse
    makeMeta(orderedBuilds, Map.empty, Seq.empty)
  }
  
  
    
  /** The unique SHA for this build. */
  def uuid: String = hashing sha1 (repeatableBuilds map (_.uuid))
  
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
        readMember[Seq[ProjectConfigAndExtracted]]("projects")
    )
    def unapply(c: ConfigValue): Option[RepeatableDistributedBuild] = 
      c match {
        case Members(projects) =>
          Some(RepeatableDistributedBuild(projects))
        case _ => None
      }
  }
}
