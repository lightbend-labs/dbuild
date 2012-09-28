package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO}

object LocalRepoHelper {
  
  def makeRawFileKey(sha: String): String =
    "raw/"+sha
  
  /**
   * Creates a new ArtifactSha sequence for each item found
   * in the locally deployment repository directory.  
   */
  def makeRawArtifactMetaData(localRepo: java.io.File): Seq[(File, ArtifactSha)] =
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
  def publishRawArtifacts(localRepo: File, remote: Repository) =
    for {
      (file, a @ ArtifactSha(sha, location)) <- makeRawArtifactMetaData(localRepo)
      key = makeRawFileKey(sha)
      _ = remote put (key, file)
    } yield a
  
  /** Grabs a set of raw artifacts and materializes them into a local
   * repository.
   */
  def materializeRawArtifacts(remoteRepo: Repository, artifacts: Seq[ArtifactSha], localRepo: File): Unit = 
    for {
      artifact <- artifacts
      file = new File(localRepo, artifact.location)
      key = makeRawFileKey(artifact.sha)
      resolved = remoteRepo get key
    } IO.copyFile(resolved, file, false)
}