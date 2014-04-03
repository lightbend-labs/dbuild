package distributed.project.build
import java.io.File
import distributed.repo.core.GlobalDirs.buildDir

/**
 * dbuild-specific file names used during building.
 * These directory names are all relative to a build directory,
 * while the global directory names are in distributed.repo.core.GlobalDirs.
 * The sbt build system has a few additional names, in SbtRunner.
 */
object BuildDirs {
  /**
   * The name of the dbuild subdir created in each project build dir to store
   * rematerialized artifacts, as well as to receive the generated artifacts.
   * This is not the same as dbuildHomeDirName.
   */
  val dbuildDirName = ".dbuild"
  /**
   * repository for incoming (rematerialized) artifacts
   */
  val inArtsDirName = "local-repo"
  /**
   * repository for outgoing (compiled/generated) artifacts
   */
  val outArtsDirName = "local-publish-repo"

  /**
   * prepare a unique subdirectory of the general build directory, into
   * which a specific project can be built.
   */
  def useProjectUniqueBuildDir[A](id: String, targetDir: File)(f: File => A) = {
    val dir = buildDir(targetDir)
    val projdir = new File(dir, id)
    projdir.mkdirs()
    f(projdir)
  }
}