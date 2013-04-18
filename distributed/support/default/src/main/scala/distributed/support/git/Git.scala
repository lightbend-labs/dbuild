package distributed
package support
package git


import sys.process._
import _root_.java.io.File
import _root_.java.net.URI
import logging.Logger

/** A git runner */
object Git {
  
  def revparse(dir: File, ref: String): String = 
    this.read(Seq("rev-parse", ref), dir).trim

  def fetch(ref: String, tempDir: File, log: Logger): Unit = {
    val args = if(ref.isEmpty) Seq("fetch")
               else Seq("fetch", ref)
    this.apply(args, tempDir, log)
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

  def run(args: Seq[String], cwd: File, log: Logger) =
    Process(OS.callCmdIfWindows("git") ++ args, cwd) ! log

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
