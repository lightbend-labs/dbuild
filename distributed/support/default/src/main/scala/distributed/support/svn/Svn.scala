package distributed
package support
package svn


import sys.process._
import _root_.java.io.File
import _root_.java.net.URI
import _root_.sbt.IO
import logging.Logger
import git.OS._
import git.UriUtil

/** A svn runner.  This class exists solely for Scalatest. */
object Svn {
  
  val Revision = new _root_.scala.util.matching.Regex("Revision: ([\\d]+)")
  
  object HasRevision {
    def unapply(in: String): Option[String] =
      (for {
        Revision(rev) <- (in split "[\r\n]+").view
      } yield rev).headOption
  }
  
  def isSvnRepo(uri: URI): Boolean =
    try IO.withTemporaryDirectory { dir =>
      read(Seq("info", (UriUtil dropFragment uri).toASCIIString), dir) match {
        case HasRevision(_) => true
        case _ => false
      }
    } catch { case e: Exception => false }
  
  def revision(cwd: File, log: Logger): String =
    read(Seq("info"), cwd, log) match {
      case HasRevision(rev) => rev
      case x => sys.error("Could not resolve SVN revision from: " + x)
    }
  
  def update(revision: String, cwd: File, log: Logger): Unit = 
    if(revision.isEmpty) apply(Seq("update"), cwd, log)
    else apply(Seq("update", "-r", revision), cwd, log)
    
  def revert(cwd: File, log: Logger): Unit =
    apply(Seq("revert", "-R", "."), cwd, log)
  
  def checkout(base: URI, tempDir: File, log: Logger): Unit = {
    // TODO - Parent file ok?
    val uri = (UriUtil dropFragment base).toASCIIString
    apply(Seq("checkout", uri, tempDir.getName), tempDir.getParentFile, log)
  }
  private def read(args: Seq[String], cwd: File): String =
    Process(callCmdIfWindows("svn") ++ args, cwd).!!
  private def read(args: Seq[String], cwd: File, log: Logger): String =
    Process(callCmdIfWindows("svn") ++ args, cwd) !! log
    
  def apply(args: Seq[String], cwd: File, log: Logger): Unit =
    Process(callCmdIfWindows("svn") ++ args, cwd) ! log match {
      case 0 => ()
      case n => sys.error("Nonzero exit code ("+n+"): svn " + (args mkString " "))
    }
}