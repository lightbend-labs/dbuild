package distributed.repo.core

import distributed.project.model._
import java.io.File
import sbt.Path._

// TODO - Locally configured area for projects
// With some kind of locking to prevent more than one 
// person from using the same directory at the same time
// Either that or spawn an actor for every local project
// and send the function to run on the actor?
// TODO - Pull from config!
object GlobalDirs {

  // Global dir names: only used to identify directories that are unique
  // in the system. (the directory names used during extraction or building
  // live instead under distributed.project.*)
  
  /**
   * The name of the internal dbuild dir created under the user home directory, used
   * to store data that should be shared among dbuild dirs (like the boot dir, and the
   * internal dbuild repo).
   */
  val dbuildHomeDirName = ".dbuild"
  /**
   * The name of the dir used to store the internal dbuild artifacts repository.
   * Created in the user home directory, under dbuildHomeDirName
   */
  val userCacheDirBaseName = "cache"
  /**
   * The name of the subdir used to store git clones, extraction dirs,
   * project build dirs, and so on. This is created in the current dir.
   */
  val targetBaseDirName = "target"
  /**
   * The name of the subdir used to store Git clones
   */
  val clonesDirName = "clones"
  /**
   * Name of the subdirectory that contains log files
   */
  val logsDirName = "logs"
  /**
   * the target subdirectory that contains all of the directories and files used during extraction.
   */
  val extractionDirName = "extraction"
  /**
   * the target subdirectory that contains all of the directories and files used during building.
   */
  val buildDirName = "project-builds"


  val baseDir = new File(".")
  val targetDirName = targetBaseDirName + "-" + Defaults.version
  val targetDir = new File(baseDir, targetDirName)
  val clonesDir = new File(targetDir, clonesDirName)
  val userhome = new File(sys.props("user.home"))
  val dbuildHomeDir = new File(userhome, dbuildHomeDirName)
  val userCacheDirName = userCacheDirBaseName + "-" + Defaults.version
  val userCache = new File(dbuildHomeDir, userCacheDirName)
  val repoCredFile = new File(dbuildHomeDir, "remote.cache.properties")
  def logDir = new File(targetDir, logsDirName)

  // there is only one target dir at this time, but these
  // two methods are parametric, just in case
  def extractionDir(tdir: File) = new File(tdir, extractionDirName)
  def buildDir(tdir: File) = new File(tdir, buildDirName)


  def checkForObsoleteDirs(f: (=> String) => Unit) = {
    def issueWarnings(root: File, baseDirName: String, dirName: String) = {
      import sbt.{ FileFilter => FF, DirectoryFilter => DF }
      root.*(DF && ((baseDirName: FF) || baseDirName + "-*") && -(dirName: FF)).get.foreach { z =>
        f("WARNING: This directory is not in use: " + z.getCanonicalPath)
      }
    }
    issueWarnings(baseDir, targetBaseDirName, targetDirName)
    issueWarnings(dbuildHomeDir, userCacheDirBaseName, userCacheDirName)
  }
}