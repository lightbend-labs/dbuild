package distributed.repo.core

import distributed.project.model._
import java.io.File
import sbt.Path._

// TODO - Locally configured area for projects
// With some kind of locking to prevent more than one 
// person from using the same directory at the same time
// Either that or spawn an actor for every local project
// and send the function to run on the actor?
object ProjectDirs {
  // TODO - Pull from config!
  val baseDir = new File(".")
  val targetBaseDirName = "target"
  val targetDirName = targetBaseDirName + "-" + Defaults.version
  val targetDir = new File(baseDir, targetDirName)
  val clonesDir = new File(targetDir, "clones")
  val userhome = new File(sys.props("user.home"))
  val dbuildDir = new File(userhome, ".dbuild")
  val userCacheDirBaseName = "cache"
  val userCacheDirName = userCacheDirBaseName + "-" + Defaults.version
  val userCache = new File(dbuildDir, userCacheDirName)
  val repoCredFile = new File(dbuildDir, "remote.cache.properties")

  def logDir = new File(targetDir, "logs")

  def extractionDir(tdir: File) = new File(tdir, "extraction")
  def projectExtractionDir(dir: File) = new File(dir, "projects")

  def buildDir(tdir: File) = new File(tdir, "project-builds")

  def useProjectExtractionDirectory[A](build: ExtractionConfig, edir: File)(f: File => A) = {
    val dir = projectExtractionDir(edir)
    val projdir = new File(dir, build.uuid)
    projdir.mkdirs()
    f(projdir)
  }

  def useProjectUniqueBuildDir[A](uuid: String, tdir: File = targetDir)(f: File => A) = {
    val dir = buildDir(tdir)
    val projdir = new File(dir, uuid)
    projdir.mkdirs()
    f(projdir)
  }
  @deprecated
  def makeDirForBuild(build: DistributedBuildConfig, tdir: File = targetDir): File = {
    val file = new File(tdir, hashing sha1 build)
    file.mkdirs()
    file
  }

  def userRepoDirFor[A](uuid: String)(f: File => A) = {
    val dir = new File(targetDir, "repositories")
    val repodir = new File(dir, uuid)
    repodir.mkdirs()
    f(repodir)
  }

  def checkForObsoleteDirs(f: (=> String) => Unit) = {
    def issueWarnings(root: File, baseDirName: String, dirName: String) = {
      import sbt.{ FileFilter => FF, DirectoryFilter => DF }
      root.*(DF && ((baseDirName: FF) || baseDirName + "-*") && -(dirName: FF)).get.foreach { z =>
        f("WARNING: This directory is no longer used, and can be removed: " + z.getCanonicalPath)
      }
    }
    issueWarnings(baseDir, targetBaseDirName, targetDirName)
    issueWarnings(dbuildDir, userCacheDirBaseName, userCacheDirName)
  }
}