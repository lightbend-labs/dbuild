package local

import distributed.project.model.BuildConfig
import java.io.File

// TODO - Locally configured area for projects
// With some kind of locking to prevent more than one 
// person from using the same directory at the same time
// Either that or spawn an actor for every local project
// and send the function to run on the actor?
object ProjectDirs {
  // TODO - Pull from config!
  private val dir = new File(".localprojects")
  
  // TODO - Check lock file or something...
  def useDirFor[A](build: BuildConfig)(f: File => A) = {
    val dir = new File(".localprojects")
    val projdir = new File(dir, hashing.sha1Sum(build))
    projdir.mkdirs()
    f(projdir)
  }
}