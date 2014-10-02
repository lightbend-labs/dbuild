package com.typesafe.dbuild.model
import java.io.File

/**
 * This currently represents a "coordinate" of an artifact, the version you must
 * rewire to depend on, and the amount of time it took to build such an artifact.
 */
case class ArtifactLocation(info: ProjectRef, version: String, crossSuffix: String, pluginAttrs: Option[SbtPluginAttrs])

/**
 * If the artifact is an sbt plugin, it will have in its extraAttributes some additional information, that we will
 * need during rewiring. We store them here, and we set pluginAttrs in ArtifactLocation to Some(SbtPluginAttrs) accordingly.
 */
case class SbtPluginAttrs(sbtVersion: String, scalaVersion: String)
/**
 * This class represents an Artifact's SHA (of the file) for unique storage and the
 * location it has in a maven/ivy/p2 repository.
 *
 * We use this to push files into artifactory and retrieve them as a workaround now.
 */
case class ArtifactSha(sha: String, location: String)

/**
 * This is the metadata a project generates after building.  We can deploy this to our repository as
 * as an immutable piece of data that is used to retrieve artifacts after the build.
 *
 * Note: The list of artifacts and files/shas is extracted for each subproject by the build system.
 */
case class ProjectArtifactInfo(
  project: RepeatableProjectBuild,
  versions: BuildArtifactsOut)

/**
 * This represents two pieces of data:
 *
 * (1) The artifacts that we need to rewire dependencies for
 * (2) The repository in which those artifacts are stored.
 *
 * BuildArtifactsIn represents "incoming artifacts to rewire", while
 * BuildArtifactsOut represents the "outgoing artifacts for publication".
 * The latter contains a sequence in which each element contains:
 * - name of a subproject
 * - artifacts published by that subproject
 * - corresponding shas of files published by that subproject to the repository.
 * The set of shas and artifacts should be related, in theory; in practice,
 * the file system is manually inspected, and any additional files that may
 * have been generated (checksums,additional metadata, etc) are grabbed as well
 * and turned into shas.
 *
 * If the build system has no subproject support, BuildArtifactsOut will contain
 * just one element, where the subproject name is the empty string.
 * 
 * The "space" field is not used to process or discover artifacts in any way
 * (that is done via the artifacts UUIDs): it is only used in diagnostic messages
 */
case class BuildArtifactsIn(artifacts: Seq[ArtifactLocation], fromSpace: String, localRepo: File)
// variant for multi-level build systems
case class BuildArtifactsInMulti(materialized: Seq /*Levels*/ [BuildArtifactsIn]) {
  // to simplify single-level build systems, the following convenience methods
  // are supplied, which only refer to the first level
  def artifacts = materialized.head.artifacts
  def localRepo = materialized.head.localRepo
}
case class BuildArtifactsOut(results: Seq[BuildSubArtifactsOut])
// moduleInfo presents a view of the generated *modules*, while artifacts and shas refer to the
// generated *artifacts* (files). The two are related, but not strictly: information like version
// or name could differ, in theory. The two different views are carried around together as part
// of a "BuildSubArtifactsOut", which also includes the name of the subproject that refers to these
// artifacts and module info.
case class BuildSubArtifactsOut(subName: String, artifacts: Seq[ArtifactLocation], shas: Seq[ArtifactSha],
    moduleInfo: com.typesafe.dbuild.manifest.ModuleInfo)

/**
 * This represents general information every dbuild must know:
 * What artifacts are coming in (from metadata) and where to
 * write new artifacts (so we can save them for later).
 * "version" is the version string that will result from the build
 * For subproj, see RepeatableProjectBuild.
 */
case class BuildInput(artifacts: BuildArtifactsInMulti, version: String, subproj: Seq /*Levels*/ [Seq[String]], outRepo: File, projectName: String)
