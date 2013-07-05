package distributed
package support
package scala

import project.BuildSystem
import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import _root_.sbt.IO.relativize
import logging.Logger
import sys.process._
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.Utils.readValue

/** Implementation of the Scala  build system. */
object ScalaBuildSystem extends BuildSystem {
  val name: String = "scala"  
  
  def extractDependencies(config: ProjectBuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    val meta=readMeta(dir)
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    meta
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, log: logging.Logger): BuildArtifactsOut = {
    Process(Seq("ant", "distpack-maven-opt"), Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    val version = input.version
    
    // Now deliver scala to the remote repo.
    // TODO - VERSIONING!!!!!!!!!!!!!!!!!!
    val localRepo = input.outRepo
    Process(Seq("ant", "deploy.local",
        "-Dlocal.snapshot.repository="+localRepo.getAbsolutePath,
        "-Dlocal.release.repository="+localRepo.getAbsolutePath,
        "-Dmaven.version.number="+version
    ), Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }

    def artifactDir(repoDir: File, ref: ProjectRef) =
      ref.organization.split('.').foldLeft(repoDir)(_ / _) / ref.name

    // Since this is a real local maven repo, it also contains
    // the "maven-metadata-local.xml" files, which should /not/ end up in the repository.
    val meta=readMeta(dir)
    BuildArtifactsOut(meta.projects map {
      proj => (proj.name,proj.artifacts map {ArtifactLocation(_, version)},
        (proj.artifacts.flatMap(ref => (artifactDir(localRepo,ref).***).get).filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml").map {
          LocalRepoHelper.makeArtifactSha(_,localRepo)
      })
    )})
  }

  /** Read ExtractedBuildMeta from dbuild-meta.json if present in the scala checkout.
   * Otherwise, fall back to hard-coded defaults.
   */
  private def readMeta(baseDir: File): ExtractedBuildMeta = {
    val dbuildMetaFile = new File(baseDir, "dbuild-meta.json")
    val readMeta=try readValue[ExtractedBuildMeta](dbuildMetaFile)
    catch { case e:Exception =>
      fallbackMeta(baseDir)
    }
    // There are no real subprojects in the Scala build;
    // simulate them by creating one per artifact.
    // That will enable us to publish individual artifacts
    // during the deploy stage.
    readMeta.copy(subproj=readMeta.projects map {_.name})
  }

  /** Read version from build.number but fake the rest of the ExtractedBuildMeta.*/
  private def fallbackMeta(baseDir: File): ExtractedBuildMeta = {
    val propsFile = new File(baseDir, "build.number")
    import util.control.Exception.catching
    def loadProps(): Option[_root_.java.util.Properties] =
     catching(classOf[_root_.java.io.IOException]) opt {
      val props = new _root_.java.util.Properties()
      props.load(new _root_.java.io.FileReader(propsFile))
      props
    }

    val version = (
      for {
        f <- if (propsFile.exists) Some(propsFile) else None
        props <- loadProps()
        major <- Option(props get "version.major")
        minor <- Option(props get "version.minor")
        patch <- Option(props get "version.patch")
      } yield major.toString+"."+minor.toString+"."+patch.toString
    ) getOrElse sys.error("unable to load scala version number!")

    // hard-coded
    ExtractedBuildMeta(version, Seq(
      Project("continuations", "org.scala-lang.plugins",
        Seq(ProjectRef("continuations", "org.scala-lang.plugins")),
        Seq.empty),
      Project("jline", "org.scala-lang",
        Seq(ProjectRef("jline", "org.scala-lang")),
        Seq.empty),
      Project("partest", "org.scala-lang",
        Seq(ProjectRef("partest", "org.scala-lang")),
        Seq(ProjectRef("scala-compiler", "org.scala-lang"), ProjectRef("scala-actors", "org.scala-lang"))),
      Project("scala-actors", "org.scala-lang",
        Seq(ProjectRef("scala-actors", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"))),
      Project("scala-actors-migration", "org.scala-lang",
        Seq(ProjectRef("scala-actors-migration", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("scala-actors", "org.scala-lang"))),
      Project("scala-compiler", "org.scala-lang",
        Seq(ProjectRef("scala-compiler", "org.scala-lang")),
        Seq(ProjectRef("scala-reflect", "org.scala-lang"), ProjectRef("jline", "org.scala-lang"))),
      Project("scala-library", "org.scala-lang",
        Seq(ProjectRef("scala-library", "org.scala-lang")),
        Seq.empty),
      Project("scala-reflect", "org.scala-lang",
        Seq(ProjectRef("scala-reflect", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"))),
      Project("scala-swing", "org.scala-lang",
        Seq(ProjectRef("scala-swing", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"))),
      Project("scalap", "org.scala-lang",
        Seq(ProjectRef("scalap", "org.scala-lang")),
        Seq(ProjectRef("scala-compiler", "org.scala-lang")))
    ))
  }
}
