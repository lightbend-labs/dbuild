package distributed.project.model
import java.io.File

/**
 * This currently represents a "coordinate" of an artifact, the version you must
 * rewire to depend on, and the amount of time it took to build such an artifact.
 * 
 * NOTE: Build time is only in here so we could hack some performance quickly.
 */
case class ArtifactLocation(info: ProjectRef, version: String, buildTime: Double = 0.0)

/** This class represents an Artifact's SHA (of the file) for unique storage and the 
 * location it has in a maven/ivy/p2 repository.
 * 
 * We use this to push files into artifactory and retrieve them as a workaround now.
 */
case class ArtifactSha(sha: String, location: String)

/** This is the metadata a project generates after building.  We can deploy this to our repository as
 * as an immutable piece of data that is used to retrieve artifacts after the build.
 * 
 * Note: As of now this can only be created after running a build and inspecting the deployed artifact files for SHA/relative paths.
 */
case class ProjectArtifactInfo(
    project: RepeatableProjectBuild,
    // (Subprojects,Relative locations
    versions: Seq[(String,Seq[ArtifactLocation])],
    artifactLocations: Seq[ArtifactSha])
  
/**
 * This represents two pieces of data:
 * 
 * (1) The artifacts that we need to rewire dependencies for
 * (2) The repository in which those artifacts are stored.
 * 
 * Unfortunately, It's currently used to represent both incoming and outgoing
 * artifacts in builds.   We must edit this so that we have explicit
 * "incoming artifacts to rewire" and "outgoing artifacts for publication".
 */
case class BuildArtifactsOut(artifacts: Seq[(String,Seq[ArtifactLocation])], localRepo: File)
case class BuildArtifactsIn(artifacts: Seq[ArtifactLocation], localRepo: File)


/** This represents general information every dbuild must know:
 * What artifacts are coming in (from metadata) and where to
 * write new artifacts (so we can save them for later).
 * "version" is the version string that will result from the build
 * 
 * Also includes the UUID of this build, in case of direct d-build integration.
 * For subproj, see RepeatableProjectBuild.
 */
case class BuildInput(artifacts: BuildArtifactsIn, uuid: String, version: String, subproj: Seq[String], outRepo: File)
