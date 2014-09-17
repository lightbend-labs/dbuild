package com.typesafe.dbuild.project.dependencies
import com.typesafe.dbuild.model._
import java.io.File

/**
 * dbuild-specific file names used during extraction. Also
 * see com.typesafe.com.dbuild.project.build.BuildDirs, SbtRunner.SbtFileNames,
 * and com.typesafe.com.dbuild.repo.core.GlobalDirs.
 */
object ExtractionDirs {
  val projectExtractionDirName = "projects"

  def projectExtractionDir(tdir: File) = new File(tdir, projectExtractionDirName)
  def useProjectExtractionDirectory[A](build: ExtractionConfig, edir: File)(f: File => A) = {
    val dir = projectExtractionDir(edir)
    val projdir = new File(dir, build.uuid)
    projdir.mkdirs()
    f(projdir)
  }

}