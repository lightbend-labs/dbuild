package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO, Path}
import Path._
import distributed.project.model.Utils.{writeValue,readValue}
import distributed.project.model.ArtifactLocation

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
      IO.write(file, writeValue(build))
      remote put (key, file)
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
  protected def publishRawArtifacts(localRepo: File, subproj: String, files: Seq[ArtifactSha], remote: Repository) = {
    if (subproj != "") println("Checking files for subproject: " + subproj)
    files foreach {
      case ArtifactSha(sha, location) =>
        val key = makeRawFileKey(sha)
        println("Checking file: " + location)
        remote put (key, localRepo / location)
    }
  }    
    
  protected def publishProjectMetadata(meta: ProjectArtifactInfo, remote: Repository): Unit = {
    val key = makeProjectMetaKey(meta.project.uuid)
    // padding string, as withTemporaryFile() will fail if the prefix is too short
    IO.withTemporaryFile(meta.project.config.name+"-padding", meta.project.uuid) { file =>
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
  def publishProjectArtifactInfo(project: RepeatableProjectBuild, extracted: Seq[(String, Seq[ArtifactLocation], Seq[ArtifactSha])],
    localRepo: File, remote: Repository): ProjectArtifactInfo = {
    extracted foreach { case (subproj, arts, shas) => publishRawArtifacts(localRepo, subproj, shas, remote) }
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
   * Artifact that project has in the repository.  It returns the projet metadata and a sequence of
   * results of the operation run against each artifact in the repository.
   */
  protected def resolveArtifacts[T](uuid: String, remote: ReadableRepository)(f: (File, ArtifactSha) => T): (ProjectArtifactInfo, Seq[T]) = {
    val metadata =
      materializeProjectMetadata(uuid, remote) getOrElse (throw new ResolveException(makeProjectMetaKey(uuid), "Could not resolve metadata for " + uuid))
    val results = for {
      artifact <- metadata.versions.flatMap{_._3}
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
  def materializeProjectRepository(uuid: String, remote: ReadableRepository, localRepo: File): (Seq[ArtifactLocation],String) = {
    val (meta, _) = resolveArtifacts(uuid, remote) { (resolved, artifact) =>
      val file = new File(localRepo, artifact.location)
      IO.copyFile(resolved, file, false)
    }
    (meta.versions.map{_._2}.flatten,meta.project.config.name+" (at: "+(new java.net.URI(meta.project.config.uri)).getFragment+")")
  }

  def getArtifactsFromUUIDs(diagnostic: (=> String) => Unit, repo: Repository, readRepo: java.io.File, uuids: Seq[String]): Seq[ArtifactLocation] =
    for {
      uuid <- uuids
      (arts,name) = LocalRepoHelper.materializeProjectRepository(uuid, repo, readRepo)
      _ = diagnostic("Retrieved from project " + name + ": " + arts.length + " artifacts")
      art <- arts
    } yield art

  def getProjectInfo(uuid: String, remote: ReadableRepository) =
    resolveArtifacts(uuid, remote)((x,y) => x -> y)
    
  /** Checks whether or not a given project (by UUID) is published. */
  def getPublishedDeps(uuid: String, remote: ReadableRepository):Seq[(String,Seq[ArtifactLocation],Seq[ArtifactSha])] = {
    // We run this to ensure all artifacts are resolved correctly.
    val (meta, results) = resolveArtifacts(uuid, remote) { (file, artifact) => () }
    meta.versions
  }
  
}