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
    val git = GitGit
    // We cache a single git bare clone for this repository URI (sans fragment),
    // then we check out just the files in the various work trees.
    // TODO: locking
    val cloneDir = findCloneDir(config)

    val (uri, uriString) = uriAndString(config)
    val ref = Option(uri.getFragment()) getOrElse "master"

    // If we want a full clone, we cannot allow shallow clones
    val shallowAllowed = !config.gitFullClone

    // TODO: clones of no longer used projects will hang around in the clones dir forever
    val cloneRepo = git.getRepo(cloneDir) getOrElse git.create(uriString, cloneDir, log)
    val sha = git.fetchOne(cloneRepo, ref, shallowAllowed, skipGitUpdates, log)
    log.info ("Reference \"" + ref + "\" resolves to " + sha)
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha // keep query if present
    config.copy(uri = newUri)
  }

  override def prepare(config: ProjectBuildConfig, workDir: _root_.java.io.File, log: Logger) = {
    val git = GitGit
    val cloneDir = findCloneDir(config)

    val (uri, uriString) = uriAndString(config)
    val cloneRepo = git.getRepo(cloneDir) getOrElse
      sys.error("Internal error: no git clone at " + cloneDir.getCanonicalPath +
      ", prepare() without resolve()?")

    val sha = Option(uri.getFragment()) getOrElse sys.error("Internal error in git/prepare: no sha?")

    // If we request a full clone, a complete git clone (not shallow) will appear in the work tree,
    // and the checkout will be taken from there
    val repo = if (config.gitFullClone) {
      val localRepoDir = workDir / ".git"
      val localRepo = git.getRepo(localRepoDir) getOrElse git.create(cloneDir.getCanonicalPath(), localRepoDir, log)
      // At this point we still have a bare repo.
      // We convert it into a non-bare
      git.unbare(localRepo, log)
      // when shallowAllowed is false, this is actually a fetchAll
      git.fetchOne(localRepo, sha, shallowAllowed = false, skipUpdates = false, log)
      // We perform a local fetch. Once that is done,
      // we also redirect origin to the original uri, just in case
      git.setOrigin(localRepo, newOrigin = UriUtil.dropFragment(uri).toASCIIString, log)
      localRepo
    } else cloneRepo

    // perform a hard reset to the desired sha
    git.prepareFiles(repo, workDir, sha, log)
  }
}
