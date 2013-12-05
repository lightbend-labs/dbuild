package distributed.project.model

import Utils.writeValue
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.  Note: That the config in this case
 * should be the "repeatable" SCM uris and full information such that we can
 * generate repeatable builds from this information.
 * Note that the global build options are not included, therefore this is not
 * the entire information that can guarantee a unique build.
 */
case class ProjectConfigAndExtracted(config: ProjectBuildConfig, extracted: ExtractedBuildMeta)

/**
 * This class represents *ALL* IMMUTABLE information about a project such
 * that we can generate a unique and recreatable UUID in which to store
 * the totally unique meta-data information for this project.  This
 * also includes all transitive dependencies so all artifacts can be
 * resolved appropriately.
 *
 * We also include the (plain) project version string detected during
 * extraction. We will later either append to it the UUID of this
 * RepeatableProjectBuild, or use the explicit string provided in
 * the "setVersion" of the ProjectBuildConfig (if not None).
 *
 * Also included (if the build system supports subprojects) is the
 * actual list of subprojects that should be built, in the correct order.
 * We use this as a payload, since the RepeatableProjectBuild gets
 * included in the BuildInput, which eventually gets to the build system
 * via LocalBuildRunner, when the project is eventually built.
 */
case class RepeatableProjectBuild(config: ProjectBuildConfig,
  @JsonProperty("base-version") baseVersion: String,
  dependencies: Seq[RepeatableProjectBuild],
  subproj: Seq[String]) {
  /** UUID for this project. */
  def uuid = hashing sha1 this

  def extra[T](implicit m: Manifest[T]) = config.getExtra[T]

  def transitiveDependencyUUIDs: Set[String] = {
    def loop(current: Seq[RepeatableProjectBuild], seen: Set[String]): Set[String] = current match {
      case Seq(head, tail @ _*) =>
        if (seen contains head.uuid) loop(tail, seen)
        else loop(tail ++ head.dependencies, seen + head.uuid)
      case _ => seen
    }
    loop(dependencies, Set.empty)
  }
}

object RepeatableDistributedBuild {
  def fromExtractionOutcome(outcome: ExtractionOK) = RepeatableDistributedBuild(outcome.pces)
}
/**
 * A distributed build containing projects in *build order*
 *  Also known as the repeatable config. Note that notifications
 *  are not included, as they have no effect on builds.
 */
case class RepeatableDistributedBuild(builds: Seq[ProjectConfigAndExtracted]) {
  def repeatableBuildConfig = DistributedBuildConfig(builds map (_.config),
      options = None)
  
  /** Our own graph helper for interacting with the build meta information. */
  lazy val graph = new BuildGraph(builds)
  /** All of our repeatable build configuration in build order. */
  lazy val buildMap = repeatableBuilds.map(b => b.config.name -> b).toMap
  lazy val repeatableBuilds: Seq[RepeatableProjectBuild] = {
    def makeMeta(remaining: Seq[ProjectConfigAndExtracted],
      current: Map[String, RepeatableProjectBuild],
      ordered: Seq[RepeatableProjectBuild]): Seq[RepeatableProjectBuild] =
      if (remaining.isEmpty) ordered
      else {
        // Pull out current repeatable config for a project.
        val head = remaining.head
        val node = graph.nodeFor(head) getOrElse sys.error("O NOES -- TODO better graph related puke message")
        val subgraph = graph.subGraphFrom(node) map (_.value)
        val dependencies =
          for {
            dep <- (subgraph - head)
          } yield current get dep.config.name getOrElse sys.error("ISSUE! Build has circular dependencies.")
        val sortedDeps = dependencies.toSeq.sortBy(_.config.name)
        val headMeta = RepeatableProjectBuild(head.config, head.extracted.version,
          sortedDeps, head.extracted.subproj) // pick defaults if no BuildOptions specified
        makeMeta(remaining.tail, current + (headMeta.config.name -> headMeta), ordered :+ headMeta)
      }
    val orderedBuilds = (graph.safeTopological map (_.value)).reverse
    makeMeta(orderedBuilds, Map.empty, Seq.empty)
  }
  
  // some initialization code (we don't need to keep around the inner vals)
  {
    // we need to check for duplicates /before/ checking for cycles, otherwise spurious
    // cycles may be detected, leading to unhelpful error messages
    // TODO: if model.Project is ever associated with the subproject name, it would be
    // more appropriate to print the actual subproject name, rather than the Project
    val generatedArtifacts = builds flatMap { _.extracted.projects } map { a => (a.organization, a.name) }
    val uniq = generatedArtifacts.distinct
    if (uniq.size != generatedArtifacts.size) {
      val conflicting = generatedArtifacts.diff(uniq).distinct
      val conflictSeq = conflicting map {
        a =>
          (builds filter {
            b =>
              b.extracted.projects.exists(p => p.organization == a._1 && p.name == a._2)
          } map { _.config.name }).mkString("  " + a._1 + "#" + a._2 + ", from:  ", ", ", "")
      }
      sys.error(conflictSeq.
        mkString("\n\nFatal: multiple projects produce the same artifacts. Please exclude them from some of the conflicting projects.\n\n", "\n", "\n"))
    }
    graph.checkCycles()
  }
}
