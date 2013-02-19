package distributed
package project
package model

import _root_.java.io.File
import config.{ConfigPrint, ConfigRead}
import ConfigPrint.makeMember
import ConfigRead.readMember
import sbt.Types.:+:
import sbt.HNil


/**
 * This currently represents a "coordinate" of an artifact, the version you must
 * rewire to depend on, and the amount of time it took to build such an artifact.
 * 
 * NOTE: Build time is only in here so we could hack some performance quickly.
 */
case class ArtifactLocation(dep: ProjectRef, version: String, buildTime: Double = 0.0)
object ArtifactLocation {
  implicit object PrettyArtLoc extends ConfigPrint[ArtifactLocation] {
    def apply(l: ArtifactLocation): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("info", l.dep)
      sb append ","
      sb append makeMember("version", l.version)
      sb append ","
      sb append makeMember("buildTime", l.buildTime)
      sb append "}"
      sb.toString
    }
  }
  /** Extractor from a ConfigValue. */
  implicit object Configured extends ConfigRead[ArtifactLocation] {
    import config._
    val Members = (
      readMember[ProjectRef]("info") :^:
      readMember[String]("version") :^:
      readMember[Double]("buildTime")
    )
    def unapply(c: ConfigValue): Option[ArtifactLocation] = {
      c match {
        case Members(dep :+: version :+: time :+: HNil) =>
          Some(ArtifactLocation(dep, version, time))
        case _ => None
      }
    }
  }  
}

/** This class represents an Artifact's SHA (of the file) for unique storage and the 
 * location it has in a maven/ivy/p2 repository.
 * 
 * We use this to push files into artifactory and retrieve them as a workaround now.
 */
case class ArtifactSha(sha: String, location: String)
object ArtifactSha {
  implicit object Configured extends ConfigPrint[ArtifactSha] with ConfigRead[ArtifactSha] {
    def apply(l: ArtifactSha): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("sha", l.sha)
      sb append ","
      sb append makeMember("location", l.location)
      sb append "}"
      sb.toString
    }
    import config._
    val Members = (
      readMember[String]("sha") :^:
      readMember[String]("location")
    )
    def unapply(c: ConfigValue): Option[ArtifactSha] = {
      c match {
        case Members(sha :+: location :+: HNil) =>
          Some(ArtifactSha(sha, location))
        case _ => None
      }
    }
  }
}

/** This is the metadata a project generates after building.  We can deploy this to our repository as
 * as an immutable piece of data that is used to retrieve artifacts after the build.
 * 
 * Note: As of now this can only be created after running a build and inspecting the deployed artifact files for SHA/relative paths.
 */
case class ProjectArtifactInfo(
    project: RepeatableProjectBuild,
    //Relative locations
    versions: Seq[ArtifactLocation],
    artifacts: Seq[ArtifactSha])
object ProjectArtifactInfo {
  implicit object Configured extends ConfigPrint[ProjectArtifactInfo] with ConfigRead[ProjectArtifactInfo] {
    def apply(l: ProjectArtifactInfo): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("project", l.project)
      sb append ","
      sb append makeMember("versions", l.versions)
      sb append ","
      sb append makeMember("artifactLocations", l.artifacts)
      sb append "}"
      sb.toString
    }
    import config._
    val Members = (
      readMember[RepeatableProjectBuild]("project") :^:
      readMember[Seq[ArtifactLocation]]("versions") :^:
      readMember[Seq[ArtifactSha]]("artifactLocations")
    )
    def unapply(c: ConfigValue): Option[ProjectArtifactInfo] = {
      c match {
        case Members(project :+: versions :+: artifacts :+: HNil) =>
          Some(ProjectArtifactInfo(project, versions, artifacts))
        case _ => None
      }
    }
  }  
}


  
/**
 * This represents two pieces of data:
 * 
 * (1) The artifacts that we need to rewire dependencies for
 * (2) The repository in which those artifacts are stored.
 * 
 * Unfortunately, It's currently used to represent both incoming and outgoing
 * artifacts in builds.   We must edit this so that we have explicit
 * "incoming artifacts to rewire" and "outgoing artifacts for publicaton".
 */
case class BuildArtifacts(artifacts: Seq[ArtifactLocation], localRepo: File)
object BuildArtifacts {
  implicit object PrettyPrinter extends ConfigPrint[BuildArtifacts] {
    def apply(r: BuildArtifacts): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("artifacts", r.artifacts)
      sb append ","
      sb append makeMember("localRepo", r.localRepo)
      sb append "}"
      sb.toString
    }
  }
  implicit object Configured extends ConfigRead[BuildArtifacts] {
    import config._
    val Members = (
      readMember[Seq[ArtifactLocation]]("artifacts") :^:
      readMember[java.io.File]("localRepo")
    )
    def unapply(in: ConfigValue): Option[BuildArtifacts] = in match {
      case Members(artifacts :+: repo :+: HNil) =>
        Some(BuildArtifacts(artifacts, repo))
      case _ => None
    }
  }
}



/** This represents general information every dbuild must know:
 * What artifacts are coming in (from metadata) and where to
 * write new artifacts (so we can save them for later).
 * 
 * Also includes the UUID of this build, in case of direct d-build integration.
 */
case class BuildInput(arts: BuildArtifacts, uuid: String, outRepo: File)
object BuildInput {
  implicit object PrettyPrinter extends ConfigPrint[BuildInput] {
    def apply(r: BuildInput): String = {
      val sb = new StringBuilder("{")
      sb append makeMember("artifacts", r.arts)
      sb append ","
      sb append makeMember("outRepo", r.outRepo)
      sb append ","
      sb append makeMember("uuid", r.uuid)
      sb append "}"
      sb.toString
    }
  }
  implicit object Configured extends ConfigRead[BuildInput] {
    import config._
    val Members = (
      readMember[BuildArtifacts]("artifacts") :^:
      readMember[java.io.File]("outRepo") :^:
      readMember[String]("uuid")
    )
    def unapply(in: ConfigValue): Option[BuildInput] = in match {
      case Members(artifacts :+: repo :+: uuid :+: HNil) =>
        Some(BuildInput(artifacts, uuid, repo))
      case _ => None
    }
  }
}  