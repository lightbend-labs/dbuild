package distributed.project.dependencies
import distributed.project.model._
import java.io.File

/**
 * dbuild-specific file names used during extraction. Also
 * see distributed.project.build.BuildDirs, SbtRunner.SbtFileNames,
 * and distributed.repo.core.GlobalDirs.
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