package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO, Path}
import Path._

object LocalRepoHelper {
  
  protected def makeRawFileKey(sha: String): String =
    "raw/"+sha
    
  protected def makeProjectMetaKey(sha: String): String =
    "meta/project/" + sha 

  protected def makeBuildMetaKey(sha: String): String =
    "meta/build/" + sha
    
  /** Publishes the given repeatable build configuration to the repository. */
  def publishBuildMeta(build: RepeatableDistributedBuild, remote: Repository): Unit =
    IO.withTemporaryFile("repeatable-build", build.uuid) { file =>
      val key = makeBuildMetaKey(build.uuid)
      IO.write(file, config makeConfigString build)
      remote put (key, file)
    }
  /**
   * Creates a new ArtifactSha sequence for each item found
   * in the locally deployment repository directory.  
   */
  protected def makeRawArtifactMetaData(localRepo: java.io.File): Seq[(File, ArtifactSha)] =
    for {
      file <- (localRepo.***).get
      _ = println("Checking file: " + file.getAbsolutePath)
      if !file.isDirectory
    } yield {
      val sha = hashing.files sha1 file
      val name = IO.relativize(localRepo, file) getOrElse file.getName
      file -> ArtifactSha(sha, name)
    }

  
  /**
   * Publishes all files in the localRepo directory by SHA and creates
   * new ArtifactSha sequence for each item found.  
   */
  protected def publishRawArtifacts(localRepo: File, remote: Repository) =
    for {
      (file, a @ ArtifactSha(sha, location)) <- makeRawArtifactMetaData(localRepo)
      key = makeRawFileKey(sha)
      _ = remote put (key, file)
    } yield a
    
    
  protected def publishProjectMetadata(meta: ProjectArtifactInfo, remote: Repository): Unit = {
    val key = makeProjectMetaKey(meta.project.uuid)
    IO.withTemporaryFile(meta.project.config.name, meta.project.uuid) { file =>
      IO.write(file, config makeConfigString meta)
      remote put (key, file)
    }
  }
    
  /**
   * Publishes the metadata for a project build.
   * 
   * @param project  The repeatable project build, used to genreate UUIDs and find dependencies.
   * @param extracted The extracted artifacts that this project generates.
   * @param remote  The repository to publish into.
   */
  def publishProjectArtiactInfo(project: RepeatableProjectBuild, extracted: Seq[ArtifactLocation], localRepo: File, remote: Repository): ProjectArtifactInfo = {
    val arts = publishRawArtifacts(localRepo, remote)
    val info = ProjectArtifactInfo(project, extracted, arts)
    publishProjectMetadata(info, remote)
    info
  }
    
    
  protected def materializeProjectMetadata(uuid: String, remote: ReadableRepository): Option[ProjectArtifactInfo] = {
    val key = makeProjectMetaKey(uuid)
    val file = remote get key
    try config.parseStringInto[ProjectArtifactInfo](IO read file)
    catch {
      case t: Throwable => throw new MalformedMetadata(key, "Unable to parse ProjectArtifactInfo metadata from: " + file.getAbsolutePath)
    }
  }
    
  /**
   * This method takes in a project UUID, a repository and a function that operates on every
   * Artifact that project has in the repository.  It returns the projet metadata and a sequence of
   * results of the operation run against eah artifact in the repository.
   */
  protected def resolveArtifacts[T](uuid: String, remote: ReadableRepository)(f: (File, ArtifactSha) => T): (ProjectArtifactInfo, Seq[T]) = {
    val metadata =
      materializeProjectMetadata(uuid, remote) getOrElse (throw new ResolveException(makeProjectMetaKey(uuid), "Could not resolve metadata for " + uuid))
    val results = for {
      artifact <- metadata.artifacts
      key = makeRawFileKey(artifact.sha)
      resolved = remote get key
    } yield f(resolved, artifact)
    
    (metadata, results)
  }
    
    
  /**
   * Materialize the artifacts for a given project UUID.
   * Does *not* pull down transitive dependencies.
   * 
   *   @param uuid  The id of the project to materialize
   *   @param remote  The repository to pull artifacts from.
   *   @param localRepo  The location to store artifacts read from the repository.
   *   @return The list of *versioned* artifacts that are now in the local repo.
   */
  def materializeProjectRepository(uuid: String, remote: ReadableRepository, localRepo: File): Seq[ArtifactLocation] = {
    val (meta, _) = resolveArtifacts(uuid, remote) { (resolved, artifact) =>
      val file = new File(localRepo, artifact.location)
      IO.copyFile(resolved, file, false)
    }
    meta.versions
  }
    
  /** Checks whether or not a given project (by UUID) is published. */
  def getPublishedDeps(uuid: String, remote: ReadableRepository): Seq[ArtifactLocation] = {
    // We run this to ensure all artifacts are resolved correctly.
    val (meta, results) = resolveArtifacts(uuid, remote) { (file, artifact) => () }
    meta.versions
  }
  
}