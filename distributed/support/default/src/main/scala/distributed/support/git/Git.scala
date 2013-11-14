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
import distributed.utils.Time.timed

/** A git runner */
object Git {

  // we add a .run() to monitorable commands, so that we can add some
  // optional output logging
  // no implicit classes yet, we are still on 2.9
  type T[A, B <: GitCommand[A]] = GitCommand[A] { def setProgressMonitor(monitor: ProgressMonitor): B }
  implicit def asRunnableCommand[A, B <: GitCommand[A]](t: T[A, B]) = new RunnableCommand(t)
  class RunnableCommand[A, B <: GitCommand[A]](c: T[A, B]) {

    def run(log: Logger) = {
      // a TextProgressMonitor() accepts a java.io.Writer. However, we need
      // our output to go to the logger instead. So we use a CharArrayWriter, hoping
      // that the TextProgressMonitor calls flush() every now and then
      val writer = new java.io.CharArrayWriter() {
        override def flush() = {
          super.flush()
          log.debug("flflflfl " + toString)
          reset()
        }
        override def close() = {
          super.close()
          log.debug("clclclcl " + toString)
          reset()
        }
      }
      //      t.setProgressMonitor(new TextProgressMonitor(writer)).call()
      val (ret,time) = timed(c.call())
      log.debug("Took: " + time)
      ret
    }
  }

  val refSpecs = Seq("+refs/pull/*/head:refs/pull/*/head",
    "+refs/tags/*:refs/tags/*", "+refs/heads/*:refs/heads/*")
  def revparse(dir: File, ref: String): String =
    this.read(Seq("rev-parse", ref), dir).trim

  // From github to our cache clone we allow failures, which may happen if we are offline
  // but we want to use our current local cache. The flag "ignoreFailures" reflects that.
  // The flag "usePR" is true if we need to add the refSpecs of GitHub's pull requests.
  def fetch(repo: JGit, uriString: String, tempDir: File, ignoreFailures: Boolean, log: Logger) = {
    val c = repo.getRepository().getConfig()
    val remotes = c.getSubsections("remote")
    log.debug("Fetching " + uriString)
    log.debug("In " + tempDir.getAbsolutePath())
    log.debug("Refspecs:")
    refSpecs foreach { rs => log.debug("  " + rs) }
    try {
      repo.fetch().setRemote("origin").setRemoveDeletedRefs(true).setTagOpt(NO_TAGS).setRefSpecs(refSpecs.map { new RefSpec(_) }: _*).run(log)
      val allRefs = repo.getRepository().getAllRefs().asScala.foreach {
        r => log.debug("found ref: " + r._1 + " -> " + ObjectId.toString(r._2.getObjectId()))
      }
//      val allBranches = repo.branchList().setListMode(ALL).call().asScala.foreach {
//        r => log.debug("found branch: " + r.getName)
//      }
    } catch {
      case t: Exception =>
        if (ignoreFailures) {
          log.warn("WARNING:")
          log.warn("WARNING: could not fetch up-to-date repository data for " + uriString)
          log.warn("WARNING:")
          val msg1 = t.getClass.getSimpleName + (Option(t.getMessage) map { ": " + _.split("\n")(0) } getOrElse "")
          if (msg1.length < 60) msg1 else msg1.take(57) + "..."
          log.debug("The message was " + msg1)
        } else throw t
    }
  }

  /**
   * Clones a project, but does not check out anything;
   *  an explicit checkout must follow
   */
  def clone(base: String, tempDir: File, log: Logger) = {
    log.info("Cloning " + base + " to " + tempDir.getCanonicalPath)
    JGit.cloneRepository().
      setURI(base).
      setDirectory(tempDir).
      setNoCheckout(true).
      setCloneAllBranches(true).
      run(log)
  }

  /**
   * jgit is a Java library; Java used not to have hardlinks,
   * therefore jgit is unable to replicate the behavior of
   * "git clone -n -l", which uses hardlinks and is therefore
   * immensely faster (and uses no additional disk space)
   * See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=362376
   *
   * Note: make sure /not/ to use a "file:/" URI, but rather
   * just a plain path (or hardlinks will not be used).
   *
   * Ideally, we would try the native git for this particular
   * functionality, and if that fails we fall back to jgit
   * gracefully.
   */
  def cloneLocal(base: String, tempDir: File, log: Logger) = {
    log.debug("Cloning (using native git and hardlinks) " + base + " to " + tempDir.getCanonicalPath)
    apply(
      Seq("clone", "-n", "-l",
        base,
        tempDir.getAbsolutePath),
      tempDir, log)
    getRepo(tempDir)
  }

  def getRepo(tempDir: File) = JGit.open(tempDir)

  // equivalent to:
  // for branch in `git branch -a | grep remotes | grep -v HEAD`; do git branch --track ${branch#remotes/origin/} $branch 2>/dev/null; done
  def setupRemoteBranches(repo: JGit, dir: File, log: Logger): Unit = {
    val allBranches = repo.branchList().setListMode(ALL).call().asScala.foreach {
      r => log.debug("found branch: " + r.getName)
    }
    val remoteBranches = repo.branchList().setListMode(REMOTE).call().asScala.filterNot(_.getName() == "refs/remotes/origin/HEAD")
    //    val walk = new RevWalk(repo.getRepository())
    remoteBranches foreach { ref =>
      val name = ref.getName().substring("refs/remotes/origin/".length)
      log.debug("setting up branch for " + name)
      //      walk.reset()
      //      val commit = walk.lookupCommit(ref.getObjectId())
      log.debug("  to " + ref.getObjectId())
      repo.branchCreate().setName(name).setUpstreamMode(TRACK).setForce(true).setStartPoint("origin/" + name).call()
    }
  }

  def checkout(tempDir: File, branch: String, log: Logger): Unit =
    apply(Seq("checkout", "-q", branch), tempDir, log)

  def isRemoteBranch(ref: String, cwd: File, log: Logger) =
    run(Seq("show-ref", "--verify", "--quiet", "refs/remotes/origin/" + ref), cwd, log) == 0

  def isTag(ref: String, cwd: File, log: Logger) =
    run(Seq("show-ref", "--verify", "--quiet", "refs/tags/" + ref), cwd, log) == 0

  def isCommitHash(ref: String, cwd: File) =
    try ref.matches("[a-fA-F0-9]{40}") && read(Seq("cat-file", "-t", ref), cwd).trim == "commit"
    catch { case e: _root_.java.lang.RuntimeException => false }

  def clean(dir: File, log: Logger): Unit =
    apply(Seq("clean", "-fdx"), dir, log)

  def version(dir: File = new File(".")): String =
    read(Seq("--version"), dir).trim

  private def read(args: Seq[String], cwd: File): String =
    Process(OS.callCmdIfWindows("git") ++ args, cwd).!!

  def run(args: Seq[String], cwd: File, log: Logger) = {
    log.debug(cwd.getAbsolutePath() + ", running: git " + args.mkString(" "))
    Process(OS.callCmdIfWindows("git") ++ args, cwd) ! log
  }

  def runIgnoreErrors(args: Seq[String], cwd: File, theLog: Logger) = {
    val llog = new Logger {
      def out(s: => String): Unit = theLog.out(s)
      def err(s: => String): Unit = ()
      def buffer[T](f: => T): T = theLog.buffer(f)
      def log(level: _root_.sbt.Level.Value, message: => String): Unit = theLog.log(level, message)
      def success(message: => String): Unit = theLog.success(message)
      def newNestedLogger(name: String): Logger = this
      def trace(t: => Throwable): Unit = theLog.trace(t)
    }
    run(args, cwd, llog)
  }

  def apply(args: Seq[String], cwd: File, log: Logger): Unit =
    run(args, cwd, log) match {
      case 0 => ()
      case n => sys.error("Nonzero exit code (" + n + "): git " + (args mkString " "))
    }
}

object OS {
  val isWindowsShell = {
    val ostype = System.getenv("OSTYPE")
    val isCygwin = ostype != null && ostype.toLowerCase.contains("cygwin")
    val isWindows = System.getProperty("os.name", "").toLowerCase.contains("windows")
    isWindows && !isCygwin
  }

  def callCmdIfWindows(cmd: String): Seq[String] =
    if (isWindowsShell) Seq("cmd", "/c", cmd)
    else Seq(cmd)
}
