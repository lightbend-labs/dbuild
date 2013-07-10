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

  private def scalaExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => ScalaExtraConfig(None,None,Seq.empty) // pick default values
    case Some(ec:ScalaExtraConfig) => ec
    case _ => throw new Exception("Internal error: scala build config options are the wrong type in project \""+config.name+"\". Please report")
  }

  def extractDependencies(config: ProjectBuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    val meta=readMeta(dir)
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    meta
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, log: logging.Logger): BuildArtifactsOut = {
    val ec = scalaExpandConfig(project.config)

    // if requested, overwrite build.number. This is unrelated to
    // the version that is possibly overridden by "set-version".
    ec.buildNumber match {
      case None =>
      case Some(BuildNumber(major,minor,patch,bnum)) => try {
        val p=new _root_.java.io.PrintWriter(dir / "build.number")
        p.println("version.major="+major)
        p.println("version.minor="+minor)
        p.println("version.patch="+patch)
        p.println("version.bnum="+bnum)
        p.close
      } catch {case e:_root_.java.io.IOException =>
        println("*** Error while overwriting build.number.")
        throw e
      }
    }

    // this is the version used for Maven. It is always read from "build.number",
    // unless it doesn't exist, in which case the version in "dbuild-meta.json"
    // is considered (if the file exists). To the version read in this manner, a
    // dbuild-specific suffix is added. However, if "set-version" is specified, its
    // value overriddes the information previously extracted from the checkout (including
    // the dbuild-specific suffix).
    val version = input.version
    
    Process(Seq("ant", ec.buildTarget getOrElse "distpack-maven-opt",
        "-Dmaven.version.number="+version) ++ ec.buildOptions, Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }

    val localRepo = input.outRepo
    Process(Seq("ant", "deploy.local",
        "-Dlocal.snapshot.repository="+localRepo.getAbsolutePath,
        "-Dlocal.release.repository="+localRepo.getAbsolutePath,
        "-Dmaven.version.number="+version
    ) ++ ec.buildOptions, Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }

    def artifactDir(repoDir: File, ref: ProjectRef) =
      ref.organization.split('.').foldLeft(repoDir)(_ / _) / ref.name

    // Since this is a real local maven repo, it also contains
    // the "maven-metadata-local.xml" files, which should /not/ end up in the repository.
    //
    // Since we know the repository format, and the list of "subprojects", we grab
    // the files corresponding to each one of them right from the relevant subdirectory.
    // We then calculate the sha, and package each subproj's results as a BuildSubArtifactsOut.
    val meta=readMeta(dir)
    BuildArtifactsOut(meta.projects map {
      proj => BuildSubArtifactsOut(proj.name,proj.artifacts map {ArtifactLocation(_, version, "")},
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
    // simulate them by creating one subproject per artifact.
    // That will enable us to publish individual artifacts
    // during the deploy stage.
    val meta=readMeta.copy(subproj=readMeta.projects map {_.name})

    // override the "dbuild.json" version with the one from "build.number" (if it exists)
    readBuildNumberFile(baseDir) map {v=>meta.copy(version=v)} getOrElse meta
  }

  def readBuildNumberFile(baseDir: File) = {
    import util.control.Exception.catching
    val propsFile = new File(baseDir, "build.number")
    def loadProps(): Option[_root_.java.util.Properties] =
      catching(classOf[_root_.java.io.IOException]) opt {
        val props = new _root_.java.util.Properties()
        props.load(new _root_.java.io.FileReader(propsFile))
        props
      }
    for {
      f <- if (propsFile.exists) Some(propsFile) else None
      props <- loadProps()
      major <- Option(props get "version.major")
      minor <- Option(props get "version.minor")
      patch <- Option(props get "version.patch")
    } yield major.toString + "." + minor.toString + "." + patch.toString
  }
    
  /** Read version from build.number but fake the rest of the ExtractedBuildMeta.*/
  private def fallbackMeta(baseDir: File): ExtractedBuildMeta = {

    val version = readBuildNumberFile(baseDir) getOrElse sys.error("unable to load scala version number!")

    def detectActorsMigration(baseDir:File) = {
       val dir1 = baseDir / "src" / "actors-migration" / "scala" / "actors"
       (dir1 / "Pattern.scala").isFile || (dir1 / "migration" / "Pattern.scala").isFile 
    }
    
    // hard-coded
    ExtractedBuildMeta(version, Seq(
      Project("continuations", "org.scala-lang.plugins",
        Seq(ProjectRef("continuations", "org.scala-lang.plugins")),
        Seq.empty),
      Project("jline", "org.scala-lang",
        Seq(ProjectRef("jline", "org.scala-lang")),
        Seq.empty),
      Project("scala-partest", "org.scala-lang",
        Seq(ProjectRef("scala-partest", "org.scala-lang")),
        Seq(ProjectRef("scala-compiler", "org.scala-lang"), ProjectRef("scala-actors", "org.scala-lang"))),
      Project("scala-actors", "org.scala-lang",
        Seq(ProjectRef("scala-actors", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"))),
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
    ) ++ (if (detectActorsMigration(baseDir))
      Seq(Project("scala-actors-migration", "org.scala-lang",
        Seq(ProjectRef("scala-actors-migration", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("scala-actors", "org.scala-lang"))))
        else Seq.empty))
  }
}
