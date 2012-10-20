package distributed
package repo
package server

import repo.core.ReadableRepository
import repo.core.LocalRepoHelper
import unfiltered.request._
import unfiltered.response._
import unfiltered.netty.cycle._

object RepoRules {
  
  /** Constructs repo rules for accessing artifacts from a specific build. */
  def buildRepoRules(cache: ReadableRepository): Plan = {
    val BuildArtifact = new BuildArtifactPath(cache)
    Planify {
      case GET(Path(BuildArtifact(file))) => FileResponse(file)
    }
  }
}

/** This class matches raw artifacts in the raw repository and
 * returns *just the files*, no directory listings, in
 * a URL usable by maven/ivy.
 */
class BuildArtifactPath(cache: ReadableRepository) {
  private def safeGetDeps(uuid: String) = 
    try LocalRepoHelper.getProjectInfo(uuid, cache)._2
    catch {
      case t: Throwable => Seq.empty
    }
  def unapply(path: String): Option[java.io.File] = path match {
    case Seg("repo" :: "build" :: uuid :: path) =>
      val fileLoc = path mkString "/"
      (for {
        build <- LocalRepoHelper.readBuildMeta(uuid, cache).toSeq
        project <- build.repeatableBuilds.view
        (file, artifact) <- safeGetDeps(project.uuid)
        if artifact.location == fileLoc
      } yield file).headOption      
    case _ => None
  }
}