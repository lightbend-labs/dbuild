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

  private def uriAndString(config: ProjectBuildConfig) = {
    val uri = new _root_.java.net.URI(config.uri)
    val uriString = UriUtil.dropFragmentAndQuery(uri).toASCIIString
    (uri, uriString)
  }

  // returns the directory where the clone dir for this project will live, and
  private def findCloneDir(config: ProjectBuildConfig) = {
    val (uri, uriString) = uriAndString(config)
    val baseName = ({s:String => if (s.endsWith(".git")) s.dropRight(4) else s})(uri.getRawPath().split("/").last)
    com.typesafe.dbuild.repo.core.GlobalDirs.clonesDir / ((hashing sha1 uriString) + "-" + baseName)
  }

  def resolve(config: ProjectBuildConfig, dir: _root_.java.io.File, log: Logger): ProjectBuildConfig = {
    if (config.useJGit.getOrElse(sys.error("Internal error: usejgit is None. Please report.")))
      sys.error("JGit is no longer supported; please use the regular git instead.")

    val git = GitGit

    // We cache a single git bare clone for this repository URI (sans fragment),
    // then we check out just the files in the various work trees.
    // TODO: locking
    val cloneDir = findCloneDir(config)

    val (uri, uriString) = uriAndString(config)
    val ref = Option(uri.getFragment()) getOrElse "master"
    val shallowAllowed = !(Option(uri.getQuery()).getOrElse("").split("&").contains("shallow=false"))

    // TODO: clones of no longer used projects will hang around in the clones dir forever
    val cloneRepo = git.getRepo(cloneDir) getOrElse git.create(uriString, cloneDir, log)
    val sha = git.fetchOne(cloneRepo, ref, skipGitUpdates, shallowAllowed, log)
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha // keep query if present
    config.copy(uri = newUri)
  }

  override def prepare(config: ProjectBuildConfig, workDir: _root_.java.io.File, log: Logger) = {
    if (config.useJGit.getOrElse(sys.error("Internal error: usejgit is None. Please report.")))
      sys.error("JGit is no longer supported; please use the regular git instead.")

    val git = GitGit
    val cloneDir = findCloneDir(config)

    val uri = new _root_.java.net.URI(config.uri)
    val cloneRepo = git.getRepo(cloneDir) getOrElse
      sys.error("Internal error: no git clone at " + cloneDir.getCanonicalPath +
      ", prepare() without resolve()?")

    val sha = Option(uri.getFragment()) getOrElse sys.error("Internal error in git/prepare: no sha?")

    // perform a hard reset to the desired sha
    git.prepareFiles(cloneRepo, workDir, sha, log)
  }
}
