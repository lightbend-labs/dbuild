package distributed
package support
package git


import sys.process._
import _root_.java.io.File
import _root_.java.net.URI

/** A git runner */
object Git {
  
  def revparse(dir: File, ref: String): String = 
    this.read(Seq("rev-parse", ref), dir).trim

    
  def fetch(ref: String, tempDir: File): Unit = {
    val args = if(ref.isEmpty) Seq("fetch")
               else Seq("fetch", ref)
    this.apply(args, tempDir)
  }
    
  /** Clones a project. */
  def clone(base: URI, tempDir: File): Unit =
    this.apply(
      Seq("clone",
        UriUtil.dropFragment(base).toASCIIString,
         tempDir.getAbsolutePath), 
      tempDir)
   
  def checkout(tempDir: File, branch: String): Unit =
    apply(Seq("checkout", "-q", branch), tempDir)	
  
		
  def clean(dir: File): Unit =
    apply(Seq("clean", "-fdx"), dir)
    
  def version(dir: File = new File(".")): String = 
    read(Seq("--version"), dir).trim
  
  private def read(args: Seq[String], cwd: File): String =
    Process(OS.callCmdIfWindows("git") ++ args, cwd).!!
  def apply(args: Seq[String], cwd: File): Unit =
    Process(OS.callCmdIfWindows("git") ++ args, cwd).! match {
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
