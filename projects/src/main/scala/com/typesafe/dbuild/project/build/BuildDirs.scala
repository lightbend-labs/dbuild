package com.typesafe.dbuild.project.build
import java.io.File
import com.typesafe.dbuild.repo.core.GlobalDirs.buildDir
import sbt.Path._

/**
 * dbuild-specific file names used during building.
 * These directory names are all relative to a build directory,
 * while the global directory names are in distributed.repo.core.GlobalDirs.
 * DO NOT place sbt-specific filenames in here: the sbt build system has
 * a few sbt-specific names and dir handling methods, which you can find in SbtRunner.
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

  /**
   * Creates a stream of local repositories into which to rematerialize artifacts
   */
  def localRepos(projectDir: File) = {
    val base = projectDir / dbuildDirName / inArtsDirName
    Stream.from(0).map { level: Int =>
      val repo = base / (level.toString)
      repo.mkdirs()
      repo
    }
  }

  /**
   * The directory where the resulting built artifacts will be stored.
   */
  def publishRepo(projectDir: File) = {
    val repo = projectDir / dbuildDirName / outArtsDirName
    repo.mkdirs()
    repo
  }
}