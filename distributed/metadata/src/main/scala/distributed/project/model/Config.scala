package distributed.project.model

// import distributed.support.sbt.SbtConfig


/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class ProjectBuildConfig(name: String, 
    system: String = "sbt",
    uri: String, 
    extra: Option[ExtraConfig]) {
  def uuid = hashing sha1 this
}

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig])


/** Configuration used for SBT and other builds.
 *  We need to have a single structure for all build systems;
 *  not all fields will be meaningful or used for all the
 *  build system. A reasonable default should be provided
 *  for all fields. */
// TODO - Autogenerate SBT versions!
case class ExtraConfig(
    `sbt-version`: String = "****", // TODO - was distributed.support.sbt.Defaults.sbtVersion,
    directory: String = "",
    `measure-performance`: Boolean = false,
    `run-tests`: Boolean = true,
    options: Seq[String] = Seq.empty,
    projects: Seq[String] = Seq.empty // if empty -> build all projects (default)
)
