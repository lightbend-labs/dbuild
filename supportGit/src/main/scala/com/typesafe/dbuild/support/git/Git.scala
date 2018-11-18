package com.typesafe.dbuild.support.git

import sys.process._
import _root_.java.io.File
import _root_.java.net.URI
import _root_.sbt.Path._
import com.typesafe.dbuild.logging.Logger
import collection.JavaConverters._
import java.io.CharArrayWriter
import com.typesafe.dbuild.utils.Time.timed
import com.typesafe.dbuild.support.OS

sealed abstract class GitImplementation {
  /** a logical descriptor of the repository */
  type Repo
  /**
    * create() prepares an empty git repo, and sets up
    * the remote as "origin", but does not fetch any
    * remote information, nor it checks out files.
    * The directory may not exist, but if it exists it
    * should be empty; existing files may be deleted.
    */
  def create(base: String, dir: File, log: Logger): Repo
  /**
    * Use getRepo() to grab the Repo information for a directory
    * that may already contains a git clone. Will return None
    * if the directory does not already contain a git repository.
    */
  def getRepo(dir: File): Option[Repo]
  /**
    * fetchOne() will retrieve just enough information from the remote
    * repository to allow for files at "ref" to be checked out. It will
    * not check out any files, however. The git history will only contain
    * the minimum possible amount of information.
    *
    * Note: in the particular case in which "ref" is a commit hash, we might
    * have to fetch the full history for all branches/tags/PRs anyway; that is
    * due to a Git limitation.
    *
    * If shallowAllowed is false, fetch all in any case.
    *
    * fetchOne() returns the resolved commit sha of the requested ref.
    */
  def fetchOne(repo: Repo, ref: String, shallowAllowed: Boolean, skipUpdates: Boolean, log: Logger): String

  /**
    * prepareFiles() will check out the files in the repository to match
    * the supplied commit sha. Only commit shas can be used as references.
    * At the end of prepareFiles(), any files that may have been present in
    * the directory, but do not belong to the checkout, will be removed.
    */
  def prepareFiles(repo: Repo, workDir: File, sha: String, log: Logger): Unit

  /**
    * Convert a bare git repo into a non-bare one.
    */
  def unbare(repo: Repo, log: Logger): Unit

  /**
    * Rewrite the origin url as specified.
    */
  def setOrigin(repo: Repo, newOrigin: String, log: Logger): Unit
}

/** A git runner */
object GitGit extends GitImplementation {

  case class GitRepo(
    sourceURI: String,
    dir: File)
  type Repo = GitRepo

  /** log a one-line message for an exception */
  protected def debugExceptionMessage(t: Exception, log: Logger): Unit = {
    val msg1 = t.getClass.getSimpleName + (Option(t.getMessage) map { ": " + _.split("\n")(0) } getOrElse "")
    if (msg1.length < 60) msg1 else msg1.take(57) + "..."
    log.debug("The message was " + msg1)
  }

  def getRepo(dir: File) = {
    if (!dir.exists) None else {
      val isGitDir = this.read(Seq("rev-parse", "--is-inside-git-dir"), dir).trim
      if (isGitDir == "true")
        Some(GitRepo(read(Seq("config", "--get", "remote.origin.url"), dir).trim, dir))
      else
        None
    }
  }

  def create(base: String, dir: File, log: Logger) = {
    if (!dir.exists) dir.mkdirs()
    log.debug("Preparing empty git repo in " + dir.getCanonicalPath)
    apply(Seq("init", "-q", "--bare"), dir, log)
    log.debug("Setting origin to " + base)
    apply(Seq("remote", "add", "origin", base), dir, log)
    GitRepo(base, dir)
  }


  // returns None if failed, Some(sha) if fetch worked.
  // fullRef is the full ref path, for instance "heads/<branch>" or "tags/<tag>"
  protected def attemptFetchOne(repo: GitRepo, fullRef: String, skipUpdates: Boolean, log: Logger): Option[String] = {
    try {
      if (!skipUpdates) {
        apply(Seq("fetch", "-f", "-u", "-q", "--depth=1", "origin") :+
          ("+" + fullRef + ":" + fullRef), repo.dir, log)
        log.debug("fetch succeeded")
      }
      val sha = revparse(repo.dir, fullRef)
      log.debug("ref resolves to: " + sha)
      Some(sha)
    } catch {
      case t: Exception =>
        debugExceptionMessage(t, log)
        None
    }
  }

  // lookup the ref, which we must have already decided is a commit hash, in the local
  // clone. If it is not already there, perform a full fetch (if we are not skipping updates)
  // and try again. If we don't find it even after a full fetch, abort.
  def lookupHash(repo: GitRepo, ref: String, skipUpdates: Boolean, log: Logger): String = {
    log.debug("Reference \"" + ref + "\" looks like a commit. Maybe it's already available?")
    try {
      revparse(repo.dir, ref)
    } catch {
      case t: Exception =>
        if (skipUpdates) {
          sys.error("The reference \"" + ref + "\" looks like a commit hash, but was not found among the hashes already present in our cache of the remote repo.")
        } else {
          log.debug("Reference \"" + ref + "\" was not available, performing full fetch...")
          fetchAll(repo, log)
          try {
            revparse(repo.dir, ref)
          } catch {
            case t: Exception =>
              sys.error("The reference \"" + ref + "\" looks like a commit hash, but was not found among the known hashes in the remote repo.")
          }
        }
    }
  }

  def fetchOne(repo: GitRepo, ref: String, shallowAllowed: Boolean, skipUpdates: Boolean, log: Logger): String = {
    if (skipUpdates) {
      log.info("Skipping git updates; trying to locate reference \"" + ref + "\"...")
    } else {
      log.info("Fetching info for reference \"" + ref + "\" from " + repo.sourceURI)
      log.info("into " + repo.dir.getCanonicalPath)
    }
    if (shallowAllowed) {
      // ref can be a branch, a tag, a pull request, or a commit sha.
      // If it looks like a commit hash and it is exactly 40 characters, we assume
      // it is indeed a commit sha, probably already resolved (fingers crossed).
      // Then, we can distinguish pull requests because we expect them to be in the form
      // "pull/nnn/head", so that is easy.
      // Otherwise, ref could be a branch, tag, or commit. We need to try each one
      // in turn. If it is neither a branch or a tag, then we may need to perform a
      // *full* fetch, since git servers won't in general allow us to fetch a specific
      // single commit (there is a git server option, but it is not available on GitHub,
      // for example).
      if (ref.matches("[a-fA-F0-9]{40}")) {
        lookupHash(repo, ref, skipUpdates, log)
      } else if (ref.startsWith("pull/") && ref.endsWith("/head")) {
        attemptFetchOne(repo, "refs/" + ref, skipUpdates, log) getOrElse
          sys.error("Reference " + ref + " looks like a pull request, but was not found in remote")
      } else {
        // tag or branch?
        attemptFetchOne(repo, "refs/heads/" + ref, skipUpdates, log) getOrElse
        (attemptFetchOne(repo, "refs/tags/" + ref, skipUpdates, log) getOrElse {
          // Hm. Does it at least /look/ like a commit hash?
          if (ref.matches("[a-fA-F0-9]{4,40}")) {
            lookupHash(repo, ref, skipUpdates, log)
          } else {
            sys.error("The reference \"" + ref + "\" was not a known branch, tag, or pull request, and doesn't look like a commit hash either.")
          }
        })
      }
    } else {
      if (!skipUpdates) {
        log.debug("Performing full fetch...")
        fetchAll(repo, log)
      }
      try {
        revparse(repo.dir, ref)
      } catch {
        case t: Exception =>
          sys.error("The reference \"" + ref + "\" is not a known branch, tag, pull request, or commit of the requested repository")
      }
    }
  }

  /**
    * fetchAll () will fetch all of the remote branches, tags, and pull request
    * references from the remote repository, complete with full history.
    */
  protected def fetchAll(repo: GitRepo, log: Logger): Unit = {
    val refSpecs = Seq("+refs/pull/*/head:refs/pull/*/head", "+refs/tags/*:refs/tags/*", "+refs/heads/*:refs/heads/*")
    try {
      val (ret, time) = timed(
        // is the repo shallow? if so, unshallow
        if ((repo.dir / "shallow" ).exists)
          // will automatically fix all branches, tags, and pull/* refs
          apply(Seq("fetch", "--unshallow", "-f", "-u", "-q", "origin") ++ refSpecs, repo.dir, log)
        else
          apply(Seq("fetch", "-f", "-u", "-q", "origin") ++ refSpecs, repo.dir, log)
      )
      log.debug("Took: " + time)
    } catch {
      case t: Exception =>
        debugExceptionMessage(t, log)
    }
  }

  def prepareFiles(repo: Repo, workDir: File, sha: String, log: Logger): Unit = {
    if (!sha.matches("[a-fA-F0-9]{40}"))
      sys.error("Internal error: this does not look like a 40-char sha: " + sha)
    val where = Seq("--work-tree=" + workDir.getCanonicalPath(), "--git-dir=" + repo.dir.getCanonicalPath())
    workDir.mkdirs()
    apply(where ++ Seq("update-ref", "--no-deref", "HEAD", sha), workDir, log)
    apply(where ++ Seq("reset", "-q", "--hard", "HEAD"), workDir, log)
    apply(where ++ Seq("clean", "-fdxq"), workDir, log)
  }

  def unbare(repo: Repo, log: Logger): Unit = {
    apply(Seq("config", "--local", "--bool", "core.bare", "false"), repo.dir, log)
    apply(Seq("config", "--local", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"), repo.dir, log)
  }

  def setOrigin(repo: Repo, newOrigin: String, log: Logger): Unit = {
    apply(Seq("remote", "set-url", "origin", newOrigin), repo.dir, log)
  }

  // if ref is not a commit, derefence until a commit is found
  private def revparse(dir: File, ref: String): String =
    this.read(Seq("rev-parse", ref + "^{commit}"), dir).trim

  private def read(args: Seq[String], cwd: File): String =
    Process(OS.callCmdIfWindows("git") ++ args, cwd).!!

  private def run(args: Seq[String], cwd: File, log: Logger) = {
    log.debug(cwd.getAbsolutePath() + ", running: git " + args.mkString(" "))
    Process(OS.callCmdIfWindows("git") ++ args, cwd) ! log
  }

  private def apply(args: Seq[String], cwd: File, log: Logger): Unit =
    run(args, cwd, log) match {
      case 0 => ()
      case n => sys.error("Nonzero exit code (" + n + "): git " + (args mkString " "))
    }
}
