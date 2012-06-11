package distributed
package support
package git


import sys.process._

/** A git runner */
object Git {
  
  def revparse(dir: java.io.File, ref: String): String = 
    this.read(Seq("rev-parse", ref), dir).trim

    
  def fetch(ref: String, tempDir: java.io.File): Unit = {
    val args = if(ref.isEmpty) Seq("fetch")
               else Seq("fetch", ref)
    this.apply(args, tempDir)
  }
    
  /** Clones a project. */
  def clone(base: java.net.URI, tempDir: java.io.File): Unit =
    this.apply(
      Seq("clone",
        UriUtil.dropFragment(base).toASCIIString,
         tempDir.getAbsolutePath), 
      tempDir)
   
  def checkout(tempDir: java.io.File, branch: String): Unit =
    apply(Seq("checkout", "-q", branch), tempDir)	
  
		
  def clean(dir: java.io.File): Unit =
    apply(Seq("clean", "-fdx"), dir)
    
  def version(dir: java.io.File = new java.io.File(".")): String = 
    read(Seq("--version"), dir).trim
  
  private def read(args: Seq[String], cwd: java.io.File): String =
    Process(OS.callCmdIfWindows("git") ++ args, cwd).!!
  def apply(args: Seq[String], cwd: java.io.File): Unit =
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
