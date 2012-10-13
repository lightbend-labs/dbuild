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
    
  protected def makeMetaKey(sha: String): String =
    "meta/project/" + sha 
  
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
    val key = makeMetaKey(meta.project.uuid)
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
    println("Publishing artifacts from: " + localRepo.getAbsolutePath)
    val arts = publishRawArtifacts(localRepo, remote)
    val info = ProjectArtifactInfo(project, extracted, arts)
    publishProjectMetadata(info, remote)
    info
  }
    
    
  protected def materializeProjectMetadata(uuid: String, remote: ReadableRepository): Option[ProjectArtifactInfo] = {
    val key = makeMetaKey(uuid)
    val file = remote get key
    config.parseStringInto[ProjectArtifactInfo](IO read file)
  }
    
  
  /** Grabs a set of raw artifacts and materializes them into a local
   * repository.
   */
  protected def materializeRawArtifacts(remoteRepo: ReadableRepository, artifacts: Seq[ArtifactSha], localRepo: File): Unit = 
    for {
      artifact <- artifacts
      file = new File(localRepo, artifact.location)
      key = makeRawFileKey(artifact.sha)
      resolved = remoteRepo get key
    } IO.copyFile(resolved, file, false)
    
    
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
    def resovleMetaData(uuid: String) =
      materializeProjectMetadata(uuid, remote) getOrElse sys.error("could not read metadata for: " + uuid)
    def resolveProject(uuid: String) = {
      val metadata = resovleMetaData(uuid)
      materializeRawArtifacts(remote, metadata.artifacts, localRepo)
      metadata
    } 
    resolveProject(uuid).versions
  }
}