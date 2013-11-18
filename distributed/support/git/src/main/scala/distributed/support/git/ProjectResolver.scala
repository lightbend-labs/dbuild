package distributed
package support
package git

import _root_.sbt.Path._
import project.model._
import project.resolve.ProjectResolver
import distributed.logging.Logger.prepareLogMsg
import distributed.support.UriUtil

/**
 * This class knows how to resolve Git projects and
 * update the build configuration for repeatable checkouts.
 */
class GitProjectResolver extends ProjectResolver {
  def canResolve(configUri: String): Boolean = {
    val uri = new _root_.java.net.URI(configUri)
    (uri.getPath != null) && ((uri.getScheme == "git") || (uri.getScheme == "jgit") ||
      (uri.getPath endsWith ".git") || ((uri.getScheme == "file") &&
        (new _root_.java.io.File(uri.getPath()) / ".git").exists))
  }

  /** 
   *  Use the scheme "jgit" if you prefer jgit (will not use hardlinks, hence more disk space will be used).
   *  The regular scheme "git" will use the command line tool by default.
   */
  def resolve(config: ProjectBuildConfig, dir: _root_.java.io.File, log: logging.Logger): ProjectBuildConfig = {
    val (git: GitImplementation, uriStart) = if (config.uri.startsWith("jgit:"))
      (GitJGit, config.uri.substring(1))
    else
      (GitGit, config.uri)
    val uri = new _root_.java.net.URI(uriStart)

    val uriString = UriUtil.dropFragment(uri).toASCIIString
    val cloneDir = distributed.repo.core.ProjectDirs.clonesDir / (hashing sha1 uriString)
    val ref = Option(uri.getFragment()) getOrElse "master"

    // We cache a single git clone for this repository URI (sans fragment),
    // then we re-clone just the local clone. Note that there are never
    // working files checked out in the cache clone: the directories
    // contain only a ".git" subdirectory.
    // TODO: locking
    val clone = if (!cloneDir.exists) {
      cloneDir.mkdirs()
      git.clone(uriString, cloneDir, log)
    } else {
      git.getRepo(cloneDir)
    }
    git.fetch(clone, true /* ignore failures */ , log)

    // Now: clone that cache into the local directory
    if (!dir.exists) dir.mkdirs()
    // NB: clone does not check out anything in particular.
    // An explicit checkout follows later
    val localRepo = if (!(dir / ".git").exists) git.clone(cloneDir.getCanonicalPath, dir, log) else git.getRepo(dir)

    git.fetch(localRepo, false /* stop on failures */ , log)
    git.clean(localRepo, log)

    val sha = git.checkoutRef(localRepo, ref, log)
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha
    config.copy(uri = newUri)
  }
}