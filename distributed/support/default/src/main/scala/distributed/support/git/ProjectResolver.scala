package distributed
package support
package git

import _root_.sbt.Path._
import project.model._
import project.resolve.ProjectResolver

/** This class knows how to resolve Git projects and
 * update the build configuration for repeatable checkouts.
 */
class GitProjectResolver extends ProjectResolver {
  def canResolve(configUri: String): Boolean = {
    val uri = new _root_.java.net.URI(configUri)    
    (uri.getPath!=null) && ((uri.getScheme == "git") || (uri.getPath endsWith ".git") || ((uri.getScheme == "file") &&
      (new _root_.java.io.File(uri.getPath()) / ".git").exists
    ))
  }

  def resolve(config: ProjectBuildConfig, dir: _root_.java.io.File, log: logging.Logger): ProjectBuildConfig = {
    val uri = new _root_.java.net.URI(config.uri)
    val uriString=UriUtil.dropFragment(uri).toASCIIString
    val cloneDir=distributed.repo.core.ProjectDirs.clonesDir / (hashing sha1 uriString)
    val ref=Option(uri.getFragment()) getOrElse "master"

    // We cache a single git clone for this repository URI (sans fragment),
    // then we re-clone just the local clone. Note that there are never
    // working files checked out in the cache clone: the directories
    // contain only a ".git" subdirectory.
    // TODO: locking
    val clone = if(!cloneDir.exists) {
      cloneDir.mkdirs()
      Git.clone(uriString, cloneDir, log)
    } else {
      Git.getRepo(cloneDir)
    }
    Git.fetch(clone, uriString, cloneDir, true /* ignore failures*/, log)

    // Now: clone into the directory or fetch
    if(!dir.exists) dir.mkdirs()
    // NB: clone does not check out anything in particular.
    // An explicit checkout follows later
    val localRepo = if(!(dir / ".git").exists) Git.clone(cloneDir.getCanonicalPath, dir, log) else Git.getRepo(dir)
    
    // Make sure we pull down all the refs from origin for our repeatable builds...
    Git.fetch(localRepo, uriString + " (cached)", dir, false /* stop on failures*/, log)
    
    // Now clean the directory so only desired artifacts are there...
    if(config.name != "scala") Git.clean(dir, log)

    // is it a /REMOTE/ branch? If so, we checkout the remote branch.
    // Note that "fetch" does not update the local branch refs
    // (but will grab remote tags)
    if(Git.isRemoteBranch(ref,dir,log))
      Git.checkout(dir, "origin/"+ref, log)
    else if (Git.isTag(ref,dir,log) || Git.isCommitHash(ref, dir))
      Git.checkout(dir, ref, log)
    else
      sys.error("The reference "+ref+" is not a valid branch, or tag, or commit of this git repository")

    val sha = Git.revparse(dir, "HEAD")
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha
    config.copy(uri = newUri)
  }
}