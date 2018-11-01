package com.typesafe.dbuild.support.git

import _root_.sbt.Path._
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.resolve.ProjectResolver
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.logging.Logger.prepareLogMsg
import com.typesafe.dbuild.support.UriUtil
import com.typesafe.dbuild.hashing

/**
 * This class knows how to resolve Git projects and
 * update the build configuration for repeatable checkouts.
 */
class GitProjectResolver(skipGitUpdates: Boolean) extends ProjectResolver {
  def canResolve(configUri: String): Boolean = {
    val uri = new _root_.java.net.URI(configUri)
    if (uri.getScheme == "jgit") {
      sys.error("JGit is no longer supported; please use the regular git instead.")
    }
    (uri.getPath != null) && ((uri.getScheme == "git") ||
      (uri.getPath endsWith ".git") || ((uri.getScheme == "file") &&
        (new _root_.java.io.File(uri.getPath()) / ".git").exists))
  }

  def resolve(config: ProjectBuildConfig, dir: _root_.java.io.File, log: Logger): ProjectBuildConfig = {
    if (config.useJGit.getOrElse(sys.error("Internal error: usejgit is None. Please report.")))
      sys.error("JGit is no longer supported; please use the regular git instead.")

    val git = GitGit

    val uri = new _root_.java.net.URI(config.uri)
    val ref = Option(uri.getFragment()) getOrElse "master"
    val shallowAllowed = !(Option(uri.getQuery()).getOrElse("").split("&").contains("shallow=false"))
    val uriString = UriUtil.dropFragmentAndQuery(uri).toASCIIString
    val baseName = ({s:String => if (s.endsWith(".git")) s.dropRight(4) else s})(uri.getRawPath().split("/").last)
    val cloneDir = com.typesafe.dbuild.repo.core.GlobalDirs.clonesDir / ((hashing sha1 uriString) + "-" + baseName)

    // We cache a single git clone for this repository URI (sans fragment),
    // then we re-clone just the local clone. Note that there are never
    // working files checked out in the cache clone: the directories
    // contain only a ".git" subdirectory.
    // TODO: locking
    val clone = git.getRepo(cloneDir) getOrElse git.create(uriString, cloneDir, log)
    if (skipGitUpdates)
      log.info("Skipping remote git update")
    else
      git.fetchOne(clone, ref, ignoreFailures = true /* no network? continue with what we already have */, shallowAllowed, log)
    // the central clone, as well as the build/extract clones, are initially
    // without any files checked out; an explicit reset() follows in prepare()
    // to set up files into the build/extracted dirs.

    // Now: use that cache to initialize the local build/extract directory
    // (note that we do not check out any files, yet. We'll do that in prepare())
    val localRepo = git.getRepo(dir) getOrElse git.create(cloneDir.getCanonicalPath, dir, log)

    // fetchOne will return the resolved commit for ref
    val sha = git.fetchOne(localRepo, ref, ignoreFailures = false /* stop on failures */ , shallowAllowed, log)
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha // keep query if present
    config.copy(uri = newUri)
  }

  override def prepare(config: ProjectBuildConfig, dir: _root_.java.io.File, log: Logger) = {
    if (config.useJGit.getOrElse(sys.error("Internal error: usejgit is None. Please report.")))
      sys.error("JGit is no longer supported; please use the regular git instead.")

    val git = GitGit

    val localRepo = git.getRepo(dir) getOrElse
      sys.error("Internal error, prepare() without resolve()? .git does not exist in "+dir.getCanonicalPath)

    val uri = new _root_.java.net.URI(config.uri)
    val sha = Option(uri.getFragment()) getOrElse sys.error("Internal error in git/prepare: no sha?")

    // perform a hard reset to the desired sha
    git.prepareFiles(localRepo, sha, log)
  }
}
