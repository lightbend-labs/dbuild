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

    // Now deliver scala to the remote repo.
    // TODO - VERSIONING!!!!!!!!!!!!!!!!!!
    val localRepo = dependencies.localRepo.getAbsolutePath
    Process(Seq("ant", "deploy.local",
        "-Dlocal.snapshot.repository="+localRepo,
        "-Dlocal.release.repository="+localRepo
    ), Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    
    // Now, return hardcoded results.
    val libDir = dir / "build" / "pack" / "lib"
    BuildArtifacts(Seq(
      ArtifactLocation(ProjectDep(scalaOrg, "scala-library"), libDir / "scala-library.jar"),
      ArtifactLocation(ProjectDep(scalaOrg, "scala-reflect"), libDir / "scala-reflect.jar"),
      ArtifactLocation(ProjectDep(scalaOrg, "scala-compiler"), libDir / "scala-compiler.jar"),
      ArtifactLocation(ProjectDep(scalaOrg, "scala-actors"), libDir / "scala-actors.jar")
    ) ++ dependencies.artifacts, 
    dependencies.localRepo)
  }
}