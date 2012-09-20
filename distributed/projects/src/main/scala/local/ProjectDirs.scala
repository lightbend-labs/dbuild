package local

import distributed.project.model.{ProjectBuildConfig, DistributedBuildConfig}
import java.io.File

// TODO - Locally configured area for projects
// With some kind of locking to prevent more than one 
// person from using the same directory at the same time
// Either that or spawn an actor for every local project
// and send the function to run on the actor?
object ProjectDirs {
  // TODO - Pull from config!
  val builddir = new File(".")
  val targetDir = new File(builddir, "target")
  
  
  def logDir = new File(targetDir, "logs")
  
  // TODO - Check lock file or something...
  def useDirFor[A](build: ProjectBuildConfig, tdir: File = targetDir)(f: File => A) = {
    val dir = new File(tdir, "projects")
    // TODO - just build name?
    val projdir = new File(dir, build.name + "-" + hashing.sha1Sum(build))
    projdir.mkdirs()
    f(projdir)
  }
  
  def makeDirForBuild(build: DistributedBuildConfig, tdir: File = targetDir): File = {
    val file = new File(tdir, hashing.sha1Sum(build))
    file.mkdirs()
    file
  }
  
  
  def userRepoDirFor[A](build:DistributedBuildConfig)(f: File => A) = {
    val dir = new File(targetDir, "repositories")
    val repodir = new File(dir, hashing.sha1Sum(build))
    repodir.mkdirs()
    f(repodir)
  }
}