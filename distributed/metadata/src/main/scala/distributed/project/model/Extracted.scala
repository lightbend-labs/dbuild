package distributed.project.model

/** A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectRef(
    name: String, 
    organization: String, 
    extension: String = "jar", 
    classifier: Option[String]) {
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
 * This includes things like dependencies.   Actually nothing else currently.
 */
case class ExtractedBuildMeta(uri: String, projects: Seq[Project]) {
  override def toString = "Build(%s, %s)" format (uri, projects.mkString("\n\t", "\n\t", "\n"))
}
