package distributed
package repo
package core

import project.model._
import java.io.File
import sbt.{ RichFile, IO, Path }
import Path._
import distributed.project.model.Utils.{ writeValue, readValue }
import distributed.project.model.ArtifactLocation
import logging.Logger

object LocalRepoHelper {

  protected def makeRawFileKey(sha: String): String =
    "raw/" + sha

  protected def makeProjectMetaKey(sha: String): String =
    "meta/project/" + sha

  protected def makeBuildMetaKey(sha: String): String =
    "meta/build/" + sha

  /** Publishes the given repeatable build configuration to the repository. */
  def publishBuildMeta(build: RepeatableDistributedBuild, remote: Repository, log: Logger): Unit =
    IO.withTemporaryFile("repeatable-build", build.uuid) { file =>
      val key = makeBuildMetaKey(build.uuid)
      // is the file already there? We might try to publish twice, as a previous run
      // failed. If so, let's check that what is there matches what we have (as a sanity
      // check) and print a message.
      val f: File = try {
        remote get key
        // if we are here the file exists, so we either match, or it's an error.
        // We might also fail to deserialize, though. We continue after the try.
      } catch {
        case e =>
          // the meta doesn't exist in the repo, or other I/O error (wrong privs, for instance).
          // we try to write, hoping we succeed.
          log.debug("While reading from repo: " + e.getMessage)
          IO.write(file, writeValue(build))
          remote put (key, file)
          log.info("Written metadata for build " + build.uuid)
          // all ok
          return
      }
      val existingBuild: RepeatableDistributedBuild = try {
        readValue[RepeatableDistributedBuild](f)
        // deserialized ok. We continue after the try
      } catch {
        case e =>
          // failed to deserialize. Should be impossible.
        log.error("The data already present in the dbuild repository for this build (uuid = " + build.uuid + ")")
        log.error("does not seem a valid repeatable build configuration. This shouldn't happen! Please report.")
        throw new Exception("Repository consistency check failed",e)
      }
      if (existingBuild == build) {
        log.info("The build metadata for this build is already in the repo (we are repeating the build)")
      } else {
        log.error("The data already present in the dbuild repository for this build (uuid = " + build.uuid + ")")
        log.error("does not match the current metadata. This shouldn't happen! Please report.")
        throw new Exception("Repository consistency check failed")
      }
    }

  def readBuildMeta(uuid: String, remote: ReadableRepository): Option[RepeatableDistributedBuild] = {
    val file = remote get makeBuildMetaKey(uuid)
    Some(readValue[RepeatableDistributedBuild](file))
  }

  def makeArtifactSha(file: File, localRepo: File) = {
    val sha = hashing.files sha1 file
    val name = IO.relativize(localRepo, file) getOrElse sys.error("Internal error while relativizing")
    ArtifactSha(sha, name)
  }

  /**
   * Publishes all files in the localRepo directory, according to the SHAs calculated
   * by the build system.
   */
  protected def publishRawArtifacts(localRepo: File, subproj: String, files: Seq[ArtifactSha], remote: Repository, log: Logger) = {
    if (subproj != "") log.info("Checking files for subproject: " + subproj)
    files foreach {
      case ArtifactSha(sha, location) =>
        val key = makeRawFileKey(sha)
        log.info("Checking file: " + location)
        remote put (key, localRepo / location)
    }
  }

  protected def publishProjectMetadata(meta: ProjectArtifactInfo, remote: Repository): Unit = {
    val key = makeProjectMetaKey(meta.project.uuid)
    // padding string, as withTemporaryFile() will fail if the prefix is too short
    IO.withTemporaryFile(meta.project.config.name + "-padding", meta.project.uuid) { file =>
      IO.write(file, writeValue(meta))
      remote put (key, file)
    }
  }

  /**
   * Publishes the metadata for a project build.
   *
   * @param project  The repeatable project build, used to generate UUIDs and find dependencies.
   * @param extracted The extracted artifacts that this project generates.
   * @param remote  The repository to publish into.
   */
  def publishProjectArtifactInfo(project: RepeatableProjectBuild, extracted: Seq[BuildSubArtifactsOut],
    localRepo: File, remote: Repository, log: Logger): ProjectArtifactInfo = {
    extracted foreach { case BuildSubArtifactsOut(subproj, _, shas) => publishRawArtifacts(localRepo, subproj, shas, remote, log) }
    val info = ProjectArtifactInfo(project, extracted)
    publishProjectMetadata(info, remote)
    info
  }

  protected def materializeProjectMetadata(uuid: String, remote: ReadableRepository): Option[ProjectArtifactInfo] = {
    val key = makeProjectMetaKey(uuid)
    val file = remote get key
    try Some(readValue[ProjectArtifactInfo](IO read file))
    catch {
      case t: Throwable => throw new MalformedMetadata(key, "Unable to parse ProjectArtifactInfo metadata from: " + file.getAbsolutePath)
    }
  }

  /**
   * This method takes in a project UUID, a repository and a function that operates on every
   * Artifact that the project has in the repository.  It returns the project metadata and a sequence of
   * results of the operation run against each artifact in the repository.
   */
  protected def resolveArtifacts[T](uuid: String,
    remote: ReadableRepository): ((File, ArtifactSha) => T) => (ProjectArtifactInfo, Seq[T], Seq[ArtifactLocation]) =
    resolvePartialArtifacts(uuid, Seq.empty, remote)

  // As above, but only for a list of subprojects. If the list is empty, grab all the files.
  // Also return the list of artifacts corresponding to the selected subprojects.
  protected def resolvePartialArtifacts[T](uuid: String, subprojs: Seq[String], remote: ReadableRepository)(f: (File, ArtifactSha) => T): (ProjectArtifactInfo, Seq[T], Seq[ArtifactLocation]) = {
    val metadata =
      materializeProjectMetadata(uuid, remote) getOrElse (throw new ResolveException(makeProjectMetaKey(uuid), "Could not resolve metadata for " + uuid))
    val fetch = if (subprojs.isEmpty) metadata.versions.map { _.subName } else {
      val unknown = subprojs.diff(metadata.versions.map { _.subName })
      if (unknown.nonEmpty) {
        sys.error(unknown.mkString("The following subprojects are unknown: ", ", ", ""))
      }
      subprojs
    }
    val artifactFiles = metadata.versions.filter { v => fetch.contains(v.subName) }.flatMap { _.shas }
    val results = for {
      artifactFile <- artifactFiles
      key = makeRawFileKey(artifactFile.sha)
      resolved = remote get key
    } yield f(resolved, artifactFile)

    val artifacts = metadata.versions.filter { v => fetch.contains(v.subName) }.flatMap { _.artifacts }

    (metadata, results, artifacts)
  }

  /**
   * Materialize the artifacts for a given project UUID.
   * Does *not* pull down transitive dependencies.
   *
   *   @param uuid  The id of the project to materialize
   *   @param remote  The repository to pull artifacts from.
   *   @param localRepo  The location to store artifacts read from the repository.
   *   @return The list of *versioned* artifacts that are now in the local repo,
   *   plus a log message as a sequence of strings.
   */
  def materializeProjectRepository(uuid: String, remote: ReadableRepository, localRepo: File): (Seq[ArtifactLocation], Seq[String]) =
    materializePartialProjectRepository(uuid, Seq.empty, remote, localRepo)

  /* Materialize only parts of a given projects, and specifically
   * those specified by the given subproject list. If the list is empty, grab everything.
   */
  def materializePartialProjectRepository(uuid: String, subprojs: Seq[String], remote: ReadableRepository,
    localRepo: File): (Seq[ArtifactLocation], Seq[String]) = {
    val (meta, _, arts) = resolvePartialArtifacts(uuid, subprojs, remote) { (resolved, artifact) =>
      val file = new File(localRepo, artifact.location)
      IO.copyFile(resolved, file, false)
    }
    val info1 = "Retrieved from project " +
      meta.project.config.name + " (commit: " + (new java.net.URI(meta.project.config.uri)).getFragment + ")"
    val info2 = ": " + arts.length + " artifacts"
    val msg = if (subprojs.isEmpty) Seq(info1 + info2) else
      Seq(subprojs.mkString(info1 + ", subprojects ", ", ", info2))
    (arts, msg)
  }

  def getArtifactsFromUUIDs(diagnostic: (=> String) => Unit, repo: Repository, readRepo: java.io.File, uuids: Seq[String]): Seq[ArtifactLocation] =
    for {
      uuid <- uuids
      (arts, msg) = LocalRepoHelper.materializeProjectRepository(uuid, repo, readRepo)
      _ = msg foreach { diagnostic(_) }
      art <- arts
    } yield art

  def getProjectInfo(uuid: String, remote: ReadableRepository) =
    resolveArtifacts(uuid, remote)((x, y) => x -> y)

  /** Checks whether or not a given project (by UUID) is published. */
  def getPublishedDeps(uuid: String, remote: ReadableRepository): Seq[BuildSubArtifactsOut] = {
    // We run this to ensure all artifacts are resolved correctly.
    val (meta, results, _) = resolveArtifacts(uuid, remote) { (file, artifact) => () }
    meta.versions
  }

}