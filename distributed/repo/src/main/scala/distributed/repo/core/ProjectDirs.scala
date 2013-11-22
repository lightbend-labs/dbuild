package distributed.repo.core

import distributed.project.model._
import java.io.File

// TODO - Locally configured area for projects
// With some kind of locking to prevent more than one 
// person from using the same directory at the same time
// Either that or spawn an actor for every local project
// and send the function to run on the actor?
object ProjectDirs {
  // TODO - Pull from config!
  val builddir = new File(".")
  val targetDir = new File(builddir, "target-"+Defaults.version)
  val clonesDir = new File(targetDir, "clones")
  val userhome = new File(sys.props("user.home"))
  val dbuildDir = new File(userhome, ".dbuild")
  val userCache = new File(dbuildDir, "cache-"+Defaults.version)
  val repoCredFile = new File(dbuildDir, "remote.cache.properties")
  
  def logDir = new File(targetDir, "logs")
  
  def useProjectExtractionDirectory[A](build: ExtractionConfig, tdir: File = targetDir)(f: File => A) = {
    val dir = new File(tdir, "projects")
    val projdir = new File(dir, build.uuid)
    projdir.mkdirs()
    f(projdir)
  }
  
  def useProjectUniqueBuildDir[A](uuid: String, tdir: File = targetDir)(f: File => A) = {
    val dir = new File(tdir, "project-builds")
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
}