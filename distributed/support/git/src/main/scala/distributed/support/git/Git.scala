package distributed
package support
package git

import sys.process._
import _root_.java.io.File
import _root_.java.net.URI
import logging.Logger
import org.eclipse.jgit.api.{ Git => JGit, _ }
import org.eclipse.jgit.storage.file._
import org.eclipse.jgit.transport._
import org.eclipse.jgit.transport.TagOpt._
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ThreadSafeProgressMonitor
import collection.JavaConverters._
import java.io.CharArrayWriter
import org.eclipse.jgit.api.ListBranchCommand.ListMode._
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode._
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.api.ResetCommand.ResetType.HARD
import org.eclipse.jgit.lib.Constants.HEAD
import distributed.utils.Time.timed

sealed abstract class GitImplementation {
  /** a logical descriptor of the repository */
  type Repo
  /** clone does not check out any branch */
  def clone(base: String, tempDir: File, log: Logger): Repo
  /**
   * Using some specially crafted refSpecs, we replicate all
   * remote refs to local ones in one go, during fetch.
   */
  def getRepo(dir: File): Repo
  def fetch(repo: Repo, ignoreFailures: Boolean, log: Logger): Unit
  def clean(repo: Repo, log: Logger): Unit
  /** return the sha of the checked out commit */
  def checkoutRef(repo: Repo, ref: String, log: Logger): String

  // private stuff below

  /**
   * References of the kind "pull/nnnn/head", for github pull requests,
   * are also supported. Further, with these refSpecs, all tags and
   * origin branches are also replicated.
   */
  protected val refSpecs = Seq("+refs/pull/*/head:refs/pull/*/head",
    "+refs/tags/*:refs/tags/*", "+refs/heads/*:refs/heads/*")

  /** internal support method for "fetch" */
  protected def tryFetch(ignoreFailures: Boolean, log: Logger, uriString: String)(fetch: => Unit): Unit = {
    try {
      fetch
    } catch {
      case t: Exception =>
        if (ignoreFailures) {
          log.warn("WARNING:")
          log.warn("WARNING: could not fetch up-to-date repository data for " + uriString)
          log.warn("WARNING:")
          printExceptionMessage(t, log)
        } else throw t
    }
  }

  /** react to an incorrect ref */
  protected def unknownRef(ref: String) =
    sys.error("The reference " + ref + " is not a valid branch, or tag, or commit of this git repository")

  /** log a one-line message for an exception */
  protected def printExceptionMessage(t: Exception, log: Logger): Unit = {
    val msg1 = t.getClass.getSimpleName + (Option(t.getMessage) map { ": " + _.split("\n")(0) } getOrElse "")
    if (msg1.length < 60) msg1 else msg1.take(57) + "..."
    log.debug("The message was " + msg1)
  }
}

/** A git runner */
object GitJGit extends GitImplementation {

  type Repo = JGit
  // we add a .run() to monitorable commands, so that we can add some
  // optional output logging or monitoring
  // no implicit classes yet, we are still on 2.9
  type T[A, B <: GitCommand[A]] = GitCommand[A] { def setProgressMonitor(monitor: ProgressMonitor): B }
  implicit def asRunnableCommand[A, B <: GitCommand[A]](t: T[A, B]) = new RunnableCommand(t)
  class RunnableCommand[A, B <: GitCommand[A]](c: T[A, B]) {
    def run(log: Logger) = {
      // a TextProgressMonitor() accepts a java.io.Writer. However, we need
      // our output to go to the logger instead; if that is desired, an
      // adapter class should be added
      // t.setProgressMonitor(new TextProgressMonitor(writer)).call()
      val (ret, time) = timed(c.call())
      log.info("Took: " + time)
      ret
    }
  }

  /**
   * Clones a project, but does not check out anything;
   *  an explicit checkout must follow.
   *
   *  Note:
   * jgit is a Java library; Java used not to have hardlinks,
   * therefore jgit is unable to replicate the behavior of
   * "git clone -n -l", which uses hardlinks and is therefore
   * immensely faster (and uses no additional disk space)
   * See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=362376
   */
  def clone(base: String, tempDir: File, log: Logger) = {
    log.info("Cloning " + base)
    log.info("to " + tempDir.getCanonicalPath)
    JGit.cloneRepository().
      setURI(base).
      setDirectory(tempDir).
      setNoCheckout(true).
      setCloneAllBranches(true).
      run(log)
  }

  // From github to our cache clone we allow failures, which may happen if we are offline
  // but we want to use our current local cache. The flag "ignoreFailures" reflects that.
  // The flag "usePR" is true if we need to add the refSpecs of GitHub's pull requests.
  def fetch(repo: JGit, ignoreFailures: Boolean, log: Logger) = {
    val dir = repo.getRepository().getDirectory().getParentFile()
    val originURI = repo.getRepository().getConfig().getString("remote", "origin", "url")
    log.info("Fetching " + originURI)
    log.info("into " + dir.getCanonicalPath)
    tryFetch(ignoreFailures, log, originURI) {
      repo.fetch().setRemote("origin").setRemoveDeletedRefs(true).
        setTagOpt(NO_TAGS).setRefSpecs(refSpecs.map { new RefSpec(_) }: _*).run(log)
    }
  }

  def getRepo(dir: File) = JGit.open(dir)

  def clean(repo: JGit, log: Logger): Unit =
    repo.clean().setCleanDirectories(true).setIgnore(false).call()

  def checkoutRef(repo: Repo, ref: String, log: Logger): String = {
    log.debug("Checking out: " + ref)

    // do not use checkout(); it is buggy, and will fail with clones() with nothing checked out
    // (it will think all files are deleted, hence all is in conflict).
    //    val r = repo.checkout().setCreateBranch(false).setForce(true).setName(ref).call()
    //
    // also, do not use repo.reset().setRef(ref), which may inexplicably return the ref
    // /before/ the reset, if setRef() points elsewhere (besides, it will change master or the
    // currently checked out branch) to point to that ref.
    //    val r = repo.reset().setRef(ref).setMode(HARD).call()
    //    val sha = ObjectId.toString(r.getObjectId())
    //
    // --> so, just move HEAD instead, and reset to that.
    val target = Option(repo.getRepository().resolve(ref)) getOrElse unknownRef(ref)
    log.debug("  pointing to " + ObjectId.toString(target))
    val refUpdate = repo.getRepository().getRefDatabase().newUpdate(HEAD, true /*detach*/ )
    refUpdate.setForceUpdate(true)
    refUpdate.setNewObjectId(target)
    refUpdate.update()
    log.debug("Now HEAD should point to " + ObjectId.toString(target))
    log.debug("hard resetting...")
    val r = repo.reset().setMode(HARD).call()
    val sha = ObjectId.toString(r.getObjectId())
    log.debug("  got commit: " + sha)
    sha
  }
}

/** A git runner */
object GitGit extends GitImplementation {

  case class GitRepo(
    sourceURI: String,
    dir: File)
  type Repo = GitRepo

  /**
   * Clones a project, but does not check out anything;
   *  an explicit checkout must follow.
   */
  def clone(base: String, tempDir: File, log: Logger) = {
    log.info("Cloning " + base)
    log.info("to " + tempDir.getCanonicalPath)
    val (ret, time) = timed(
      apply(
        Seq("clone", "-n", "-q",
          base,
          tempDir.getAbsolutePath),
        tempDir, log))
    log.info("Took: " + time)
    GitRepo(base, tempDir)
  }

  // From github to our cache clone we allow failures, which may happen if we are offline
  // but we want to use our current local cache. The flag "ignoreFailures" reflects that.
  // The flag "usePR" is true if we need to add the refSpecs of GitHub's pull requests.
  def fetch(repo: GitRepo, ignoreFailures: Boolean, log: Logger) = {
    log.info("Fetching " + repo.sourceURI)
    log.info("into " + repo.dir.getCanonicalPath)
    tryFetch(ignoreFailures, log, repo.sourceURI) {
      val (ret, time) = timed(
        // will automatically fix all branches, tags, and pull/* refs
        apply(Seq("fetch", "-f", "-u", "-q", "origin") ++ refSpecs, repo.dir, log))
      log.info("Took: " + time)
    }
  }

  def getRepo(dir: File) =
    GitRepo(read(Seq("config", "--get", "remote.origin.url"), dir).trim, dir)

  def checkoutRef(repo: Repo, ref: String, log: Logger): String =
    try {
      checkout(repo.dir, ref, log)
      revparse(repo.dir, "HEAD")
    } catch {
      case t: Exception =>
        printExceptionMessage(t, log)
        unknownRef(ref)
    }

  def clean(repo: GitRepo, log: Logger): Unit =
    apply(Seq("clean", "-fdx"), repo.dir, log)

  private def checkout(tempDir: File, branch: String, log: Logger): Unit =
    apply(Seq("reset", "-q", "--hard", branch), tempDir, log)

  private def isRemoteBranch(ref: String, cwd: File, log: Logger) =
    run(Seq("show-ref", "--verify", "--quiet", "refs/remotes/origin/" + ref), cwd, log) == 0

  private def isTag(ref: String, cwd: File, log: Logger) =
    run(Seq("show-ref", "--verify", "--quiet", "refs/tags/" + ref), cwd, log) == 0

  private def isCommitHash(ref: String, cwd: File) =
    try ref.matches("[a-fA-F0-9]{40}") && read(Seq("cat-file", "-t", ref), cwd).trim == "commit"
    catch { case e: _root_.java.lang.RuntimeException => false }

  private def revparse(dir: File, ref: String): String =
    this.read(Seq("rev-parse", ref), dir).trim

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
