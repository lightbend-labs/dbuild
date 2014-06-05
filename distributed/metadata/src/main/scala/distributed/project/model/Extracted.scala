package distributed.project.model
import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }

/**
 * A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectRef(
  name: String,
  organization: String,
  extension: String = "jar",
  classifier: Option[String] = None) {
  override def toString = organization + ":" + name + ":" + (classifier map (_ + ":") getOrElse "") + extension
}

/**
 * Represents extracted Project information in a build.  A project is akin to a
 * deployed artifact for a given build, and may have dependencies.
 */
case class Project(
  name: String,
  organization: String,
  artifacts: Seq[ProjectRef],
  dependencies: Seq[ProjectRef])

/**
 * Describes the project and dependency information of a project.
 * subproj is the list of (sbt or other) subprojects that will have to be compiled,
 * already sorted in build order (it will be necessary to build these subprojects
 * in this particular order in order to satisfy inter-subproject dependencies).
 * The subproj list can be empty for build systems that do not support subprojects.
 */
case class ProjMeta(version: String, projects: Seq[Project], subproj: Seq[String] = Seq.empty)

/**
 * Represents the *Extracted* metadata of a build.
 *
 * This includes things like dependencies.
 * The "version" string is the one extracted by dbuild; it may be overridden
 * with an explicit "set-version" in the project configuration.
 * projInfo is a *sequence* since each element represents the information for one
 * level of systems that work recursively, or with multiple spaces.
 * The order of projInfo elements mirrors the spaces as defined by the
 * infinite sequence obtained from "spaces.from".
 * In the case of sbt, the first element is used for the artifacts and dependencies
 * of the main build, the second element for the plugins, the third for the
 * plugins of the plugins (if any), and so on.
 * "version" and "projects" of the levels above the first are of no particular use.
 */
case class ExtractedBuildMeta(@JsonProperty("proj-info") projInfo: Seq /*Levels*/ [ProjMeta]) {
  // do NOT define secondary constructors, otherwise the Jacks/Jackson library may get quite confused

  def getHead = (projInfo.headOption getOrElse sys.error("Internal Error: Empty ProjInfo in ExtractedBuildMeta"))
  // compatibility, only base level
  def subproj = getHead.subproj
  def version = getHead.version
  /**
   * This is a convenience method to obtain the list of projects defined at the first (ground) level.
   * Note that we ignore the exact list of "projects", and the generated artifacts, for everything except the first level,
   * as we don't "produce" or publish anything from the above levels. Nonetheless, we still get a list of
   * generated artifacts from sbt's extraction of the plugin levels). For the upper levels, we are really only
   * interested in the dependencies.
   * "project" here only refers to artifacts that we are going to publish.
   */
  def projects = getHead.projects

  override def toString = "ExtractedBuildMeta(%s)" format (
    projInfo.zipWithIndex.map {
      case (ProjMeta(version, projects, subproj), index) =>
        "%s -> (%s, %s, %s)" format (index, version, projects.mkString("\n\t", "\n\t", "\n"), subproj.mkString("\n  ", ", ", "\n"))
    })
}
object ExtractedBuildMeta {
  def apply(version: String, projects: Seq[Project], subproj: Seq[String] = Seq.empty): ExtractedBuildMeta =
    ExtractedBuildMeta(Seq /*Levels*/ (ProjMeta(version, projects, subproj)))
}