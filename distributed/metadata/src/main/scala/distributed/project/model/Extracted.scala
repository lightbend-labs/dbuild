package distributed.project.model

/** A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectRef(
    name: String, 
    organization: String, 
    extension: String = "jar", 
    classifier: Option[String]=None) {
  override def toString = organization + ":" + name + ":" + (classifier map (_ + ":") getOrElse "") + extension
}

/** Represents extracted Project information in a build.  A project is akin to a
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
case class ProjMeta(projects: Seq[Project], subproj:Seq[String] = Seq.empty)

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
 */
case class ExtractedBuildMeta(version: String, projInfo: Seq[ProjMeta]) {
    // compatibility
    def this(version: String, projects: Seq[Project], subproj:Seq[String] = Seq.empty) =
      this(version, Seq(ProjMeta(projects, subproj)))
    // compatibility, only base level
    def subproj = (projInfo.headOption getOrElse sys.error("Internal Error: Empty ProjInfo in ExtractedBuildMeta")).subproj

    override def toString = "ExtractedBuildMeta(%s, %s)" format (version,
    projInfo.zipWithIndex.map {
      case (ProjMeta(projects, subproj), index) =>
        "%s -> (%s, %s)" format (index, projects.mkString("\n\t", "\n\t", "\n"), subproj.mkString("\n  ", ", ", "\n"))
    })
    /**
     * This is a convenience method to obtain the list of projects defined at the first (ground) level.
     * Note that we ignore the exact list of "projects", and the generated artifacts, for everything except the first level,
     * but we still get one from sbt's extraction of the plugin levels). For the upper levels, we get the information
     * during sbt's extraction, but we are really only interested in the dependencies.
     * "project" here only refers to artifacts that we are going to publish.
     */
    def projects = (projInfo.headOption getOrElse sys.error("Internal error: projInfo contains nothing!")).projects
}
