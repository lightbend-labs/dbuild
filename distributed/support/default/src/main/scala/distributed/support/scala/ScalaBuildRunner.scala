package distributed
package support
package scala

import project.model._
import _root_.java.io.File
import sys.process._
import _root_.sbt.Path._


object ScalaBuildRunner extends project.BuildRunner {
  val system: String = "scala"
  val scalaOrg = "org.scala-lang"
  // TODO - Publish to an ok repository for others to find the artifacts...
  def runBuild(b: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    Process(Seq("ant", "distpack-maven-opt"), Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    // Now reading version number
    val version = readScalaVersion(dir)
    
    // Now deliver scala to the remote repo.
    // TODO - VERSIONING!!!!!!!!!!!!!!!!!!
    val localRepo = dependencies.localRepo.getAbsolutePath
    Process(Seq("ant", "deploy.local",
        "-Dlocal.snapshot.repository="+localRepo,
        "-Dlocal.release.repository="+localRepo,
        "-Dmaven.version.number="+version
    ), Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    
    // Now, return hardcoded results.
    val libDir = dir / "build" / "pack" / "lib"

    BuildArtifacts(Seq(
      ArtifactLocation(ProjectDep("scala-library", scalaOrg), libDir / "scala-library.jar", version),
      ArtifactLocation(ProjectDep("scala-reflect", scalaOrg), libDir / "scala-reflect.jar", version),
      ArtifactLocation(ProjectDep("scala-compiler", scalaOrg), libDir / "scala-compiler.jar", version),
      ArtifactLocation(ProjectDep("scala-actors", scalaOrg), libDir / "scala-actors.jar", version),
      ArtifactLocation(ProjectDep("scala-swing", scalaOrg), libDir / "scala-swing.jar", version),
      ArtifactLocation(ProjectDep("scala-actors-migration", scalaOrg), libDir / "scala-actors-migration.jar", version),
      ArtifactLocation(ProjectDep("scalap", scalaOrg), libDir / "scalap.jar", version),
      ArtifactLocation(ProjectDep("jline", scalaOrg), libDir / "jline.jar", version),
      ArtifactLocation(ProjectDep("partest", scalaOrg), libDir / "paretst.jar", version),
      ArtifactLocation(ProjectDep("continuations", scalaOrg+".plugins"), dir / "build/pack/misc/scala-devel/plugins/continuations.jar", version)
    ) ++ dependencies.artifacts, 
    dependencies.localRepo)
  }
  
  private def readScalaVersion(baseDir: File): String = {
    val propsFile = new File(baseDir, "build/quick/classes/library/library.properties")
    import util.control.Exception.catching
    def loadProps(file: File): Option[_root_.java.util.Properties] = 
     catching(classOf[_root_.java.io.IOException]) opt {
      val props = new _root_.java.util.Properties()
      props.load(new _root_.java.io.FileReader(propsFile))
      props
    }
    val version: Option[String] = for {
      f <- if (propsFile.exists) Some(propsFile) else None
      props <- loadProps(f)
      version <- Option(props get "version.number")
    } yield version.toString
    version getOrElse sys.error("unable to load scala version number!")
  }
}