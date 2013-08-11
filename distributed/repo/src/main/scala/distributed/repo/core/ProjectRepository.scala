package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO, Path}
import Path._

/** This class provides utilities for dealing with a project repository. */
class ReadableProjectRepository(val remote: ReadableRepository) {
  /**
   * Materialize the artifacts for a given project UUID.
   * Does *not* pull down transitive dependencies.
   * 
   *   @param uuid  The id of the project to materialize
   *   @param remote  The repository to pull artifacts from.
   *   @param localRepo  The location to store artifacts read from the repository.
   *   @return The list of *versioned* artifacts that are now in the local repo, and the project name
   */
  def materializeArtifactRepository(uuid: String, localRepo: File, log:logging.Logger): (Seq[ArtifactLocation],Seq[String]) =
    LocalRepoHelper.materializeProjectRepository(uuid, remote, localRepo)
    
  /** Checks whether or not a given project (by UUID) is published. 
   * Returns the artifacts that are recorded in the repository.
   * 
   * Note: This will resolve artifacts to the local and throw an exception
   * if unable to do so!
   */
  def getPublishedArtifacts(uuid: String): Seq[BuildSubArtifactsOut] = 
    LocalRepoHelper.getPublishedDeps(uuid, remote)
    
    
  def getProjectInfo(uuid: String) = LocalRepoHelper.getProjectInfo(uuid, remote)
}

/** this class provides utilities for reading/publishing to a project repository.
 * (layered on top of the generic key-file repository).
 */
class ProjectRepository(remote: Repository) extends ReadableProjectRepository(remote) {
  /**
   * Publishes the metadata for a project build.
   * 
   * @param project  The repeatable project build, used to genreate UUIDs and find dependencies.
   * @param extracted The extracted artifacts that this project generates.
   * @param localRepo  The location of all artifacts we should read and send.
   */
  def publishArtifactInfo(project: RepeatableProjectBuild, extracted: Seq[BuildSubArtifactsOut], localRepo: File, log:logging.Logger): ProjectArtifactInfo =
    LocalRepoHelper.publishProjectArtifactInfo(project, extracted, localRepo, remote, log)
}