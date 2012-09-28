package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO}
import distributed.project.model.RepeatableProjectBuild$

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
      file <- (IO listFiles localRepo).toSeq
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
    IO.withTemporaryFile(meta.project.config.name, meta.version) { file =>
      IO.write(file, config makeConfigString meta)
      remote put (key, file)
    }
  }
    
  
  def publishProjectArtiactInfo(project: RepeatableProjectBuild, version: String, localRepo: File, remote: Repository): ProjectArtifactInfo = {
    val arts = publishRawArtifacts(localRepo, remote)
    val info = ProjectArtifactInfo(project, version, arts)
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
    
    
    
  def materializeProjectRepository(uuid: String, remote: ReadableRepository, localRepo: File): Unit = {
    def resovleMetaData(uuid: String) =
      materializeProjectMetadata(uuid, remote) getOrElse sys.error("could not read metadata for: " + uuid)
    def resolveProject(uuid: String) = {
      val metadata = resovleMetaData(uuid)
      materializeRawArtifacts(remote, metadata.artifacts, localRepo)
      val newUUIDs = metadata.project.dependencies map (_.uuid)
      metadata
    } 
    val metadata = resolveProject(uuid)
    metadata.project.transitiveDependencyUUIDs foreach resolveProject
  }
}