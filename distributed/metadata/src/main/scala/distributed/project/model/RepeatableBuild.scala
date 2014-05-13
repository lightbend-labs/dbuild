package distributed.project.model

import Utils.{ writeValue, canSeeSpace }
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.  Note: That the config in this case
 * should be the "repeatable" SCM uris and full information such that we can
 * generate repeatable builds from this information.
 * Note that the global build options are not included, therefore this is not
 * the entire information that can guarantee a unique build.
 */
case class ProjectConfigAndExtracted(config: ProjectBuildConfig, extracted: ExtractedBuildMeta) {
  // in theory space should never be None
  def getSpace = config.space getOrElse sys.error("Internal error: space is None in " + config.name)
}

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
  // see below the description of RepeatableDepInfo for details
  depInfo: Seq /*Levels*/ [RepeatableDepInfo]) {
  /** UUID for this project. */
  def uuid = hashing sha1 this

  def extra[T](implicit m: Manifest[T]) = config.getExtra[T]
  def getCommit = try Option((new java.net.URI(config.uri)).getFragment) catch {
    case e: java.net.URISyntaxException => None
  }
}

/**
 * This structure contains information related to each level of a multi-level
 * build. The first level is the normal project info, the second could be
 * the sbt plugins level, and so on, depending on the build system. These
 * levels reflect the fromStream sequence in the project space descriptor.
 */
case class RepeatableDepInfo(
  @JsonProperty("base-version") baseVersion: String,
  // The list of dependencies is transitive (within the boundaries
  // of the relevant spaces, see below)
  dependencyNames: Seq[String], // names corresponding to a RepeatableProjectBuild
  dependencyUUIDs: Seq[String], // uuids corresponding to a RepeatableProjectBuild
  // dependencyUUIDs and dependencyNames refer to the same elements. They are
  // in two separate sequences for convenience, as in the code there is no
  // assumption anywhere that they should be kept in sync. If that need should arise,
  // the two Seqs should probably be converted into a Seq[(String,String)].
  //
  // Subproj is the list of subprojects that will be compiled on this level
  subproj: Seq[String])

object RepeatableDistributedBuild {
  def fromExtractionOutcome(outcome: ExtractionOK) = RepeatableDistributedBuild(outcome.pces)
}
/**
 * A distributed build containing projects in *build order*
 *  Also known as the repeatable config. Note that notifications
 *  are not included, as they have no effect on builds.
 */
case class RepeatableDistributedBuild(builds: Seq[ProjectConfigAndExtracted]) {
  def repeatableBuildConfig = DistributedBuildConfig(builds map (_.config), options = None)
  /** The unique SHA for this build. */
  def uuid: String = hashing sha1 (repeatableBuilds map (_.uuid))

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
        val node = graph.nodeFor(head) getOrElse sys.error("Internal error: graph.nodeFor() was None. Please report.")
        val subgraph = graph.subGraphFrom(node) map (_.value)
        // we need this number of levels: head.extracted.projInfo.length
        // now, for each level, we find the relevant space and calculate
        // the list of dependent projects
        val allDependencies = (head.extracted.projInfo zip head.getSpace.fromStream) map {
          case (info, fromSpace) =>
            val dependencies = for {
              // only list as dependencies those that have produced artifacts that
              // the project in "head" can actually see. These will be the dependent projects
              // that are eventually reloaded (rematerialized) right before each project build starts
              //
              // Note that (at this time) the set of rematerialized projects may be larger than
              // necessary. For example: if both the first and second level of a project have "from"
              // set to the same space, and the second level depends on a certain project, the first
              // level will get a fictitious (but harmless) dependency on it as well. That is due to
              // the fact that our dependency graph is not between individual levels, but only between
              // projects as a whole. (we would need to change the dependency graph to avoid that)
              //
              dep <- (subgraph - head) if canSeeSpace(fromSpace, dep.getSpace.to)
            } yield current get dep.config.name getOrElse sys.error("Internal error: unexpected circular dependency. Please report.")
            val sortedDeps = dependencies.toSeq.sortBy(_.config.name)
            RepeatableDepInfo(info.version, sortedDeps.map(_.config.name), sortedDeps.map(_.uuid), info.subproj)
        }
        val headMeta = RepeatableProjectBuild(head.config,
          allDependencies) // pick defaults if no BuildOptions specified
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
    case class Origin(fromProject: String, spaces: SeqString)
    case class Info(artOrg: String, artName: String, origin: Origin)
    val generatedArtifacts = builds flatMap { b => b.extracted.projects.map { a => Info(a.organization, a.name, Origin(b.config.name, b.getSpace.to)) } }
    val byArt = (generatedArtifacts.groupBy { case Info(org, name, origin) => (org, name) }).toSeq
    val collisions = byArt flatMap {
      case ((org, name), seqInfo) =>
        val origins = seqInfo.map { _.origin }
        // this could probably be further optimized,
        // but hopefully the collision sets are of modest size
        for {
          List(one, two) <- origins.combinations(2)
          colliding <- Utils.collidingSeqSpaces(one.spaces, two.spaces)
        } yield (org, name, one.fromProject, two.fromProject, colliding)
    }
    if (collisions.nonEmpty) {
      val msgs = collisions.map {
        case (org, name, fromOne, fromTwo, space) =>
          "  " + org + "#" + name + "  from " + fromOne + " and " + fromTwo + ", both visible in space \"" + space + "\""
      }
      sys.error(msgs.mkString("\n\nFatal: multiple projects have the same artifacts visible in the same space.\n\n", "\n", "\n"))
    }
    graph.checkCycles()
  }
}

// This is the structure that is saved in meta/build
case class SavedConfiguration(expandedDBuildConfig: DBuildConfiguration, fullBuild: RepeatableDistributedBuild) {
  def uuid = hashing sha1 this
}
