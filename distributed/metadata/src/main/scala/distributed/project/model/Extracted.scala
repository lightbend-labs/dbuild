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

/** Represents the *Extracted* metadata of a build.
 * 
 * This includes things like dependencies.
 * The "version" string is the one extracted by dbuild; it may be overridden
 * with an explicit "set-version" in the project configuration.
 * subproj is the list of (sbt or other) subprojects that will have to be compiled,
 * in the right order. It can be empty for build systems that do not support subprojects.
 */
case class ExtractedBuildMeta(uri: String, version: String, projects: Seq[Project], subproj: Seq[String] = Seq.empty) {
  override def toString = "Build(%s, %s, %s, %s)" format (uri, version, projects.mkString("\n\t", "\n\t", "\n"), subproj.mkString("\n  ",", ","\n"))
}
