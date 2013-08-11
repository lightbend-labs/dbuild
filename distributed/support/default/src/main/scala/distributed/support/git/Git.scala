package distributed
package support
package git


import sys.process._
import _root_.java.io.File
import _root_.java.net.URI
import logging.Logger

/** A git runner */
object Git {
  
  def refSpecs = Seq("+refs/heads/*:refs/remotes/origin/*",
      "+refs/pull/*/head:refs/remotes/origin/pull/*/head") 

    def revparse(dir: File, ref: String): String = 
    this.read(Seq("rev-parse", ref), dir).trim


  // from our cache clone to the working repo
  def fetch(ref: String, tempDir: File, usePR:Boolean, log: Logger): Unit = {
    val extra = if (usePR) refSpecs else Seq[String]()
    val args = if(ref.isEmpty) sys.error("fetch with no argument")
               else Seq("fetch",ref)++extra
    this.apply(args, tempDir, log)
    val args2 = if(ref.isEmpty) sys.error("fetch with no argument")
                else Seq("fetch","-t",ref)++extra
    this.apply(args2, tempDir, log)
  }

  // from github to our cache clone.
  // we allow failures, which may happen if we are offline
  // but we want to use our current local cache
  def fetchSafe(uriString: String, tempDir: File, usePR:Boolean, log: Logger): Unit = {
    val extra = if (usePR) refSpecs else Seq[String]()
    try {
      apply(Seq("fetch", "origin")++extra, tempDir, log)
      apply(Seq("fetch", "-t", "origin")++extra, tempDir, log)
    } catch {
      case e: Exception =>
        log.warn("WARNING:")
        log.warn("WARNING: could not fetch up-to-date repository data for " + uriString)
        log.warn("WARNING:")
    }
  }

  /** Clones a project, but does not check out anything;
    *  an explicit checkout must follow
    */
  def clone(base: URI, tempDir: File, log: Logger): Unit =
    this.apply(
      Seq("clone","-n",
        UriUtil.dropFragment(base).toASCIIString,
         tempDir.getAbsolutePath), 
      tempDir, log)

  /** Clones a project, but does not check out anything;
    *  an explicit checkout must follow.
    *  The clone takes place using local paths, rather than URIs,
    *  and with the option '-l', in order to save space & time
    */
  def cloneLocal(clone: File, tempDir: File, log: Logger): Unit =
    apply(
      Seq("clone","-n","-l",
        clone.getAbsolutePath(),
         tempDir.getAbsolutePath), 
      tempDir, log)

      // equivalent to:
  // for branch in `git branch -a | grep remotes | grep -v HEAD`; do git branch --track ${branch#remotes/origin/} $branch 2>/dev/null; done
  def setupRemoteBranches(dir: File, log: Logger): Unit = {
    val branches = read(Seq("branch", "-a"), dir).split("\n").filter(_.startsWith("  remotes/origin/")).
      map { _.substring("  remotes/origin/".length) } filterNot (_.startsWith("HEAD "))
    branches foreach { b =>
      runIgnoreErrors(Seq("branch", "--track", b, "remotes/origin/" + b), dir, log)
      run(Seq("update-ref", "refs/heads/" + b, "remotes/origin/" + b), dir, log)
      }
  }
         
  def checkout(tempDir: File, branch: String, log: Logger): Unit =
    apply(Seq("checkout", "-q", branch), tempDir, log)	

  def isRemoteBranch(ref: String, cwd: File, log: Logger) = 
    run(Seq("show-ref","--verify","--quiet","refs/remotes/origin/"+ref),cwd,log) == 0
		
  def isTag(ref: String, cwd: File, log: Logger) = 
    run(Seq("show-ref","--verify","--quiet","refs/tags/"+ref),cwd,log) == 0

  def isCommitHash(ref: String, cwd: File) = 
    try ref.matches("[a-fA-F0-9]{40}") && read(Seq("cat-file","-t",ref),cwd).trim == "commit"
      catch { case e:_root_.java.lang.RuntimeException => false }

  def clean(dir: File, log: Logger): Unit =
    apply(Seq("clean", "-fdx"), dir, log)
    
  def version(dir: File = new File(".")): String = 
    read(Seq("--version"), dir).trim
  
  private def read(args: Seq[String], cwd: File): String =
    Process(OS.callCmdIfWindows("git") ++ args, cwd).!!
  
  def run(args: Seq[String], cwd: File, log: Logger) = {
    log.debug(cwd.getAbsolutePath()+", running: git "+args.mkString(" "))
    Process(OS.callCmdIfWindows("git") ++ args, cwd) ! log
  }

  def runIgnoreErrors(args: Seq[String], cwd: File, theLog: Logger) = {
    val llog=new Logger {
      def out(s: => String): Unit = theLog.out(s)
      def err(s: => String): Unit = ()
      def buffer[T](f: => T): T = theLog.buffer(f)
      def log(level: _root_.sbt.Level.Value, message: => String): Unit = theLog.log(level, message)
      def success(message: => String): Unit = theLog.success(message)
      def newNestedLogger(name: String): Logger = this
      def trace(t: => Throwable): Unit = theLog.trace(t)
    }
    run(args,cwd,llog)
  }

  def apply(args: Seq[String], cwd: File, log: Logger): Unit =
    run(args,cwd,log) match {
      case 0 => ()
      case n => sys.error("Nonzero exit code ("+ n + "): git " + (args mkString " "))
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
    if(isWindowsShell) Seq("cmd", "/c", cmd)
    else Seq(cmd)
}
