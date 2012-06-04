package com.typesafe.sbt.distributed
package support
package git


import meta._

import sys.process._
import _root_.sbt.Path._

/** This class knows how to resolve Git projects and
 * update the build configuration for repeatable checkouts.
 */
class GitProjectResolver extends ProjectResolver {
  def canResolve(config: BuildConfig): Boolean = {
    val uri = new java.net.URI(config.uri)    
    (uri.getScheme == "git") || (uri.getPath endsWith ".git")
  }
  def resolve(config: BuildConfig, dir: java.io.File): BuildConfig = {
    val uri = new java.net.URI(config.uri)

    // First clone into the directory or fetch
    // TODO - better git checkout detection...
    if(!dir.exists) dir.mkdirs()
    if(!(dir / ".git").exists) Git.clone(uri, dir)
    else Git.fetch("", dir)
    // TODO - Fetch non-standard references?
    // Then checkout desired branch/commit/etc.
    Option(uri.getFragment()) foreach (ref => Git.checkout(dir, ref))
    val sha = Git.revparse(dir, "HEAD")
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + sha
    config.copy(uri = newUri)
  }
}

object UriUtil {
  def dropFragment(base: java.net.URI): java.net.URI = 
    if(base.getFragment eq null) base 
    else new java.net.URI(base.getScheme, base.getSchemeSpecificPart, null)
}

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
