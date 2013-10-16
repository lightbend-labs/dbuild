package distributed
package support
package scala

import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import _root_.sbt.IO.relativize
import logging.Logger
import sys.process._
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.Utils.{ writeValue, readValue }
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner
import collection.JavaConverters._
import org.apache.maven.model.{ Model, Dependency }
import org.apache.maven.model.io.xpp3.{ MavenXpp3Reader, MavenXpp3Writer }
import org.apache.maven.model.Dependency
import distributed.support.NameFixer.fixName

/** Implementation of the Scala  build system. */
object ScalaBuildSystem extends BuildSystemCore {
  val name: String = "scala"

  private def scalaExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => ScalaExtraConfig(None, None, Seq.empty, Seq.empty, None) // pick default values
    case Some(ec: ScalaExtraConfig) => ec
    case _ => throw new Exception("Internal error: scala build config options are the wrong type in project \"" + config.name + "\". Please report")
  }

  private def modulesDir(base: File, config: ProjectBuildConfig) = {
    // don't use the entire nested project config, as it changes after resolution (for submodules)
    // also, avoid using the name as-is as the last path component (it might confuse the dbuild's heuristic
    // used to determine sbt's default project names, see dbuild's issue #66)
    val uuid = hashing sha1 config.name
    base / ".dbuild-scala-modules" / uuid
  }

  // overriding resolve, as we need to resolve its nested projects as well
  override def resolve(config: ProjectBuildConfig, dir: File, extractor: Extractor, log: Logger): ProjectBuildConfig = {
    // resolve the main URI
    val rootResolved = super.resolve(config, dir, extractor, log)
    // and then the nested projects (if any)
    val newExtra = rootResolved.extra match {
      case None => None
      case Some(extra: ScalaExtraConfig) =>
        val newModules = extra.modules map { buildConfig =>
          val nestedResolvedProjects =
            buildConfig.projects.foldLeft(Seq[ProjectBuildConfig]()) { (s, p) =>
              log.info("----------")
              log.info("Resolving module: " + p.name)
              val nestedExtractionConfig = ExtractionConfig(p, buildConfig.options getOrElse BuildOptions())
              val moduleConfig = extractor.dependencyExtractor.resolve(nestedExtractionConfig.buildConfig, modulesDir(dir, p), extractor, log)
              s :+ moduleConfig
            }
          DistributedBuildConfig(nestedResolvedProjects, buildConfig.options)
        }
        Some(extra.copy(modules = newModules))
      case _ => throw new Exception("Internal error: scala build config options are the wrong type in project \"" + config.name + "\". Please report")
    }
    rootResolved.copy(extra = newExtra)
  }

  def extractDependencies(config: ExtractionConfig, dir: File, extractor: Extractor, log: Logger): ExtractedBuildMeta = {
    val ec = scalaExpandConfig(config.buildConfig)
    // for the root (main Scala project):
    val meta = readMeta(dir, ec.exclude, log)
    val configAndExtracted = ProjectConfigAndExtracted(config.buildConfig, meta) // use the original config

    // we consider the names of modules in the same way as subprojects, allowing for a
    // partial deploy, exclusions, etc.
    val coreSubProjects = meta.subproj
    val moduleSubProjects = ec.modules.toSeq.flatMap(_.projects).map(_.name)
    val subProjects = coreSubProjects ++ moduleSubProjects
    if (subProjects != subProjects.distinct) {
      sys.error(subProjects.diff(subProjects.distinct).distinct.mkString("These subproject names appear twice: ", ", ", ""))
    }
    val moduleOutcomes = ec.modules.toSeq flatMap { buildConfig =>
      buildConfig.projects map { p =>
        log.info("----------")
        val nestedExtractionConfig = ExtractionConfig(p, buildConfig.options getOrElse BuildOptions())
        extractor.extractedResolvedWithCache(nestedExtractionConfig, modulesDir(dir, p), log)
      }
    }
    if (moduleOutcomes.exists(_.isInstanceOf[ExtractionFailed])) {
      sys.error(moduleOutcomes.filter { _.isInstanceOf[ExtractionFailed] }.map { _.project }.mkString("failed: ", ", ", ""))
    }
    val modulesOK = moduleOutcomes.collect({ case e: ExtractionOK => e })
    val allConfigAndExtracted = (modulesOK flatMap { _.pces }) :+ configAndExtracted

    // time to do some more checking:
    // - do we have a duplication in provided artifacts?
    // let's start building a sequence of all modules, with the name of the subproject they come from
    val artiSeq = allConfigAndExtracted.flatMap { pce => pce.extracted.projects.map(art => ((art.organization + "#" + art.name), pce.config.name)) }
    log.info(artiSeq.toString)
    // group by module ID, and check for duplications
    val artiMap = artiSeq.groupBy(_._1)
    log.info(artiMap.toString)
    val duplicates = artiMap.filter(_._2.size > 1)
    if (duplicates.nonEmpty) {
      duplicates.foreach { z =>
        log.error(z._2.map(_._2).mkString(z._1 + " is provided by: ", ", ", ""))
      }
      sys.error("Duplicate artifacts found in project")
    }

    // ok, now we just have to merge everything together. There is currently no support for
    // artifacts with different version strings within the same project, so we flatten everything
    // and pretend that all artifacts have the same version number.
    val newMeta = ExtractedBuildMeta(meta.version, allConfigAndExtracted.flatMap(_.extracted.projects),
      meta.subproj ++ moduleOutcomes.map { _.project })
    log.info(newMeta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    newMeta
  }

  // runBuild() is called with the Scala source code already in place (resolve() called on the root project),
  // but not for the submodules. After building the core, we will call localBuildRunner.checkCacheThenBuild() on each module,
  // which will in turn resolve it and then build it (if not already in cache).
  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut = {
    val ec = scalaExpandConfig(project.config)

    // if requested, overwrite build.number. This is unrelated to
    // the version that is possibly overridden by "set-version".
    ec.buildNumber match {
      case None =>
      case Some(BuildNumber(major, minor, patch, bnum)) => try {
        val p = new _root_.java.io.PrintWriter(dir / "build.number")
        p.println("version.major=" + major)
        p.println("version.minor=" + minor)
        p.println("version.patch=" + patch)
        p.println("version.bnum=" + bnum)
        p.close
      } catch {
        case e: _root_.java.io.IOException =>
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

    val meta = readMeta(dir, ec.exclude, log)
    log.info(meta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    Process(Seq("ant", ec.buildTarget getOrElse "distpack-maven-opt",
      "-Dmaven.version.number=" + version) ++ ec.buildOptions, Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }

    val localRepo = input.outRepo
    Process(Seq("ant", "deploy.local",
      "-Dlocal.snapshot.repository=" + localRepo.getAbsolutePath,
      "-Dlocal.release.repository=" + localRepo.getAbsolutePath,
      "-Dmaven.version.number=" + version) ++ ec.buildOptions, Some(dir / "dists" / "maven" / "latest")) ! log match {
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

    def projSHAs(artifacts: Seq[ProjectRef]): Seq[ArtifactSha] = {
      // use the list of artifacts as a hint as to which directories should be looked up,
      // but actually scan the dirs rather than using the list of artifacts (there may be
      // additional files like checksums, for instance). An additional consistency check
      // is later performed by the caller of the build system, upon return.
      artifacts.map(artifactDir(localRepo, _)).distinct.flatMap { _.***.get }.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml").map {
          LocalRepoHelper.makeArtifactSha(_, localRepo)
        }
    }

    def getScalaArtifactsOut() = BuildArtifactsOut(meta.projects map {
      proj =>
        BuildSubArtifactsOut(proj.name, proj.artifacts map { ArtifactLocation(_, version, "") },
          projSHAs(proj.artifacts))
    })

    val scalaArtifactsOut = getScalaArtifactsOut()

    // ok, we have built the main compiler/library/etc. Do we have any modules
    // that need to be built? If so, build them now.
    val (artifactsMap, repeatableProjectBuilds) = (ec.modules.toSeq flatMap { build =>
      build.projects map { p =>
        // the modules are build ENTIRELY independently from one another. Their list
        // of dependencies is cleared before building, so that they do not rely on one another

        // first, we need to build a RepeatableProjectBuild. In order to do that, we need
        // again the ExtractedBuildMeta, but we no longer have it (it was dissolved into the
        // main one). So, we extract again (it is cached at this point, anyway).

        log.info("----------")
        log.info("Building module: " + p.name)
        val nestedExtractionConfig = ExtractionConfig(p, build.options getOrElse BuildOptions())
        val moduleConfigAndExtracted = localBuildRunner.extractor.cachedExtractOr(nestedExtractionConfig, log) {
          // if it's not cached, something wrong happened.
          sys.error("Internal error: extraction metadata not found for module " + p.name)
        } match {
          case outcome: ExtractionOK => outcome.pces.headOption getOrElse
            sys.error("Internal error: PCES empty after cachedExtractOr(); please report")
          case _ => sys.error("Internal error: cachedExtractOr() returned incorrect outcome; please report.")
        }
        val repeatableProjectBuild = RepeatableProjectBuild(moduleConfigAndExtracted.config, moduleConfigAndExtracted.extracted.version,
          Seq.empty, // remove all dependencies, and pretend this project stands alone
          moduleConfigAndExtracted.extracted.subproj, build.options getOrElse BuildOptions())
        val outcome = localBuildRunner.checkCacheThenBuild(modulesDir(dir, p), repeatableProjectBuild, Seq.empty, Seq.empty, log)
        val artifactsOut = outcome match {
          case o: BuildGood => o.artsOut
          case o: BuildBad => sys.error("Module " + p.name + ": " + o.status)
        }
        log.debug("artifacts from module " + p.name + ": " + writeValue(artifactsOut))
        ((p.name, artifactsOut), repeatableProjectBuild)
      }
    }).unzip
    val moduleArtifacts = artifactsMap.map { _._2 }

    // excellent, we now have in moduleArtifacts a sequence of BuildArtifactsOut from the modules,
    // and in scalaArtifactsOut the BuildArtifactsOut from the core. We just need to mix them
    // together, while rewiring the various poms together so that they all refer to one another.
    //
    // Note that we are going to change the poms now, therefore we have to recalculate the shas
    //
    // ------
    //
    // The scala artifacts are already deployed via the "deploy.local" ant task to the localRepo dir.
    // now, we retrieve the artifacts' modules (they have already been published)
    val uuids = repeatableProjectBuilds map { _.uuid }
    log.info("Retrieving module artifacts")
    log.debug("into " + localRepo)
    val artifactLocations = LocalRepoHelper.getArtifactsFromUUIDs(log.info, localBuildRunner.repository, localRepo, uuids)
    //
    // we know that the Scala version is "version", we have all our artifacts ready. Time to rewrite the POMs!
    //
    // Let's collect the list of available artifacts:
    //
    val allArtifactsOut = moduleArtifacts :+ scalaArtifactsOut
    val available = allArtifactsOut.flatMap { _.results }.flatMap { _.artifacts }
    log.debug("Available artifacts:")
    available.foreach { a =>
      log.debug(a.info.organization + "#" + a.info.name + " " +
        (a.info.classifier getOrElse "") + " " + a.info.extension + " ; " + a.version + " (cross: \"" + a.crossSuffix + "\")")
    }

    (localRepo.***.get).filter(_.getName.endsWith(".pom")).map {
      pom =>
        val reader = new MavenXpp3Reader()
        val model = reader.read(new _root_.java.io.FileReader(pom))
        // transform dependencies
        val deps: Seq[Dependency] = model.getDependencies.asScala
        val newDeps: _root_.java.util.List[Dependency] = (deps map { m =>
          available.find { artifact =>
            artifact.info.organization == m.getGroupId &&
              artifact.info.name == fixName(m.getArtifactId)
          } map { art =>
            val m2 = m.clone
            m2.setArtifactId(fixName(m.getArtifactId) + art.crossSuffix)
            m2.setVersion(art.version)
            log.debug("Changed " + m + " to " + m2 + " in " + pom.getName)
            m2
          } getOrElse m
        }).asJava
        val newModel = model.clone
        newModel.setDependencies(newDeps)
        // we overwrite in place, there should be no adverse effect at this point
        val writer = new MavenXpp3Writer
        writer.write(new _root_.java.io.FileWriter(pom), newModel)
    }

    // SHAs must be re-computed (since the POMs changed), and the ArtifactsOuts must be merged
    val out = BuildArtifactsOut(getScalaArtifactsOut().results ++ artifactsMap.map {
      case (project, arts) =>
        val modArtLocs = arts.results.flatMap { _.artifacts }
        BuildSubArtifactsOut(project, modArtLocs, projSHAs(modArtLocs.map { _.info }))
    })
    
    out
  }

  /**
   * Read ExtractedBuildMeta from dbuild-meta.json if present in the scala checkout.
   * Otherwise, fall back to hard-coded defaults.
   * Also, exclude any subprojects listed in the "exclude" list; they will still
   * be listed, but will not be published or advertised as available.
   */
  private def readMeta(baseDir: File, exclude: Seq[String], log: logging.Logger): ExtractedBuildMeta = {
    val dbuildMetaFile = new File(baseDir, "dbuild-meta.json")
    val readMeta = try readValue[ExtractedBuildMeta](dbuildMetaFile)
    catch {
      case e: Exception =>
        log.error("Failed to read scala metadata file: " + dbuildMetaFile.getAbsolutePath)
        logging.Logger.prepareLogMsg(log, e)
        log.error("Falling back to default.")
        fallbackMeta(baseDir)
    }
    // There are no real subprojects in the Scala build;
    // simulate them by creating one subproject per artifact.
    // That will enable us to publish individual artifacts
    // during the deploy stage.
    // Also filter according to the "exclude" list; however,
    // any dependencies on excluded subprojects will be preserved.
    val allSubProjects = readMeta.projects map { _.name }
    val meta = if (exclude.nonEmpty) {
      val notFound = exclude.diff(allSubProjects)
      if (notFound.nonEmpty) sys.error(notFound.mkString("These subprojects were not found in scala: ", ", ", ""))
      val subProjects = allSubProjects.diff(exclude)
      readMeta.copy(subproj = subProjects).copy(projects = readMeta.projects.filter {
        p => subProjects.contains(p.name)
      })
    } else readMeta.copy(subproj = allSubProjects)

    // override the "dbuild.json" version with the one from "build.number" (if it exists)
    readBuildNumberFile(baseDir) map { v => meta.copy(version = v) } getOrElse meta
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

    def detectActorsMigration(baseDir: File) = {
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
        Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("scala-compiler", "org.scala-lang"), ProjectRef("scala-actors", "org.scala-lang"))),
      Project("scala-actors", "org.scala-lang",
        Seq(ProjectRef("scala-actors", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"))),
      Project("scala-compiler", "org.scala-lang",
        Seq(ProjectRef("scala-compiler", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("scala-reflect", "org.scala-lang"), ProjectRef("jline", "org.scala-lang"))),
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
        Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("scala-compiler", "org.scala-lang")))) ++ (if (detectActorsMigration(baseDir))
        Seq(Project("scala-actors-migration", "org.scala-lang",
        Seq(ProjectRef("scala-actors-migration", "org.scala-lang")),
        Seq(ProjectRef("scala-library", "org.scala-lang"), ProjectRef("scala-actors", "org.scala-lang"))))
      else Seq.empty))
  }
}
