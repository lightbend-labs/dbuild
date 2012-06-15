package distributed
package project

import files.{GetFile, AddFile, FileFound, FileNotFound, RepositoryNotFound}
import model.{ProjectDep, BuildArtifacts, ArtifactLocation, Build, BuildConfig}
import akka.actor.{Actor, ActorRef, Props}

case class FindBuildCache(build: Build)
case class CacheBuildArtifacts(a: BuildArtifacts, config: BuildConfig)


case class BuildCacheMiss(build: Build)

object BuildCacheUri {
  def makeUri(p: ProjectDep, config: BuildConfig) =
    (hashing sha1Sum config) + "/" + (hashing sha1Sum p)
}

import BuildCacheUri._

/** A cache that can store build artifacts. 
 *
 * TODO - make this peristent?
 * TODO - Clean up stale cache files?
 */
class BuildCache(localRepo: ActorRef) extends Actor {
  def receive = {
    case CacheBuildArtifacts(a, config) =>
      for(art <- a.artifacts) storeArtifact(art, config)
    case FindBuildCache(build) =>
      findBuildCache(build)
  }
  
  def findBuildCache(build: Build) = {
    val listener = context.actorOf(Props(new BuildCacheListener(sender, build)))
    for(project <- build.extracted.projects) {
      val dep = ProjectDep(project.name, project.organization)
      val uri = makeUri(dep, build.config)
      localRepo.!(GetFile(uri))(listener)
    }
  }
  
  def storeArtifact(loc: ArtifactLocation, config: BuildConfig) = {
    val uri = makeUri(loc.dep, config)
    // TODO - Copy file somewhere unique?
    localRepo ! AddFile(uri, loc.local)
  }
}

/** Listens for all file responses for build artifacts and responds
 * to the listener with either a cache miss, or the build artifacts.
 */
case class BuildCacheListener(listener: ActorRef, build: Build) extends Actor {
  var artifacts = BuildArtifacts(Nil)
  def receive = {
    case FileNotFound(_) | RepositoryNotFound(_) =>
      listener ! BuildCacheMiss(build)
      // TOOD - Remaining gathered messages?
      context stop self
    case FileFound(uri, loc) =>
      projectFor(uri) match {
        case Some(proj) =>
          val arts = ArtifactLocation(proj, loc) +: artifacts.artifacts
          artifacts = BuildArtifacts(arts)
          if(isDone) {
            listener ! artifacts
            context stop self
          }
        case _ => sys.error("Unknown file found: " + uri + " at + " + loc)
      }
  }
  
  def projectFor(uri: String): Option[ProjectDep] =
    (for {
      project <- build.extracted.projects
      dep = ProjectDep(project.name, project.organization)
      if uri == makeUri(dep, build.config)
    } yield dep).headOption
  
  // TODO - Speed this up.
  def isDone: Boolean =
    build.extracted.projects forall { p =>
      val pdep = ProjectDep(p.name, p.organization)
      artifacts.artifacts exists (_.dep == pdep)
    }
}