package distributed
package support
package scala

import project.model._
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import _root_.java.io.File
import _root_.sbt.Path._
import _root_.sbt.IO
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
import org.apache.ivy.util.ChecksumHelper
import distributed.support.NameFixer.fixName
import _root_.sbt.NameFilter

/** Implementation of the Scala  build system. */
object ScalaBuildSystem extends BuildSystemCore {
  val name: String = "scala"

  private def scalaExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => ScalaExtraConfig(None, None, None, Seq.empty, Seq.empty, None) // pick default values
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

    // ok, now we just have to merge everything together.
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

    // We do a bunch of in-place file operations in the localRepo, before returning.
    // To avoid problems due to stale files, delete all contents before proceeding.
    IO.delete(localRepo.*("*").get)

    Process(Seq("ant", ec.deployTarget getOrElse "deploy.local",
      "-Dlocal.snapshot.repository=" + localRepo.getAbsolutePath,
      "-Dlocal.release.repository=" + localRepo.getAbsolutePath,
      "-Dmaven.version.number=" + version) ++ ec.buildOptions, Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }

    // initial part of the artifacts dir, including only the organization
    def orgDir(repoDir: File, organization: String) =
      organization.split('.').foldLeft(repoDir)(_ / _)
    def artifactDir(repoDir: File, ref: ProjectRef, crossSuffix: String) =
      orgDir(repoDir, ref.organization) / (ref.name + crossSuffix)

    // try to find the right cross suffix and version by inspecting the org dir,
    // and looking for poms
    def findCrossAndVer(repoDir: File, org: String, name: String): (String, String) = {
      def modID = org + "#" + name
      val od = orgDir(repoDir, org)
      // possible patterns:
      // org-with-dots/name/var/name-ver.pom
      // org-with-dots/name_suff/var/name_suff-ver.pom
      val nameFilter = (name: NameFilter) | ((name + "_*"): NameFilter)
      val potentialDirs = od.*(nameFilter).get
      if (potentialDirs.isEmpty) {
        sys.error("Cannot find artifacts dir for: " + modID)
      }
      // due to the renaming we perform later, we might actually end up
      // with multiple directories (some empty or containing only
      // the "maven-metadata-local.xml" file.
      //
      // let's look for var/nameWithCross-ver.pom
      val SearchPattern = """([^/]*)/([^/]*)-\1.pom""".r
      val crossSuffixesAndVers = potentialDirs.flatMap { d =>
        d.***.get.filterNot(file => file.isDirectory).map { f =>
          val relative = relativize(d, f) getOrElse sys.error("Internal error in relative paths creation. Please report.")
          relative match {
            case SearchPattern(ver, nameAndCross) =>
              if (d.getName != nameAndCross) {
                log.error("Found unexpected pom " + f.getName + " in dir " + d.getName + "/" + relative)
              }
              Some((nameAndCross.substring(name.length), ver))
            case _ => None
          }
        }.flatten
      }
      if (crossSuffixesAndVers.isEmpty) {
        sys.error("Cannot find any pom file for " + modID)
      } else if (crossSuffixesAndVers.length > 1) {
        sys.error("Found multiple pom files for " + modID + ": " + (crossSuffixesAndVers.mkString(", ")))
      }
      log.debug("For " + modID + " the cross suffix is \"" + crossSuffixesAndVers.head + "\"")
      crossSuffixesAndVers.head
    }

    // Since this is a real local maven repo, it also contains
    // the "maven-metadata-local.xml" files, which should /not/ end up in the repository.
    //
    // Since we know the repository format, and the list of "subprojects", we grab
    // the files corresponding to each one of them right from the relevant subdirectory.
    // We then calculate the sha, and package each subproj's results as a BuildSubArtifactsOut.

    def scanFiles[Out](artifacts: Seq[ProjectRef], crossSuffix: String)(f: File => Out) = {
      // use the list of artifacts as a hint as to which directories should be looked up,
      // but actually scan the dirs rather than using the list of artifacts (there may be
      // additional files like checksums, for instance).
      artifacts.map(artifactDir(localRepo, _, crossSuffix)).distinct.flatMap { _.***.get }.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml").map(f)
    }

    def projSHAs(artifacts: Seq[ProjectRef], crossSuffix: String): Seq[ArtifactSha] = scanFiles(artifacts, crossSuffix) {
      LocalRepoHelper.makeArtifactSha(_, localRepo)
    }

    // Scala artifacts used to be always with a plain version number. However, now some modules may be built
    // as part of the core; they should use the suffix "org.scala-lang.modules". To complicate matters, however,
    // the suffix may not necessarily match the scala version we are trying to build.
    // Therefore we need to inspect the files (we try to locate the poms), and determine the proper cross
    // suffix for each.
    //
    // We need to "rewire" the dependencies of the latter projects as if they were originating
    // from external subprojects. So, we split the two.
    // Return a pair: the BuildArtifactsOut of core, and those of the modules generated by the same ant task
    // Each of these modules generate their own BuildArtifactsOut
    def getScalaArtifactsOut() = {
      val (c, m) = (meta.projects map {
        proj =>
          val (cross, ver) = findCrossAndVer(localRepo, proj.organization, proj.name)
          val sub = BuildSubArtifactsOut(proj.name, proj.artifacts map { ArtifactLocation(_, ver, cross) },
            projSHAs(proj.artifacts, cross))
          // Let's keep left the core, right the modules (map project -> sub
          if (cross == "") (Some(sub), None) else (None, Some(sub))
      }).unzip
      val scalaArtifactsCore = BuildArtifactsOut(c.flatten)
      val scalaArtifactsModulesMap = m.flatten.map { s => (s.subName, BuildArtifactsOut(Seq(s))) }
      (scalaArtifactsCore, scalaArtifactsModulesMap)
    }

    val (scalaCore, scalaCoreModulesMap) = getScalaArtifactsOut()

    log.debug("scalaCore: " + writeValue(scalaCore))
    log.debug("scalaCoreModulesMap: " + writeValue(scalaCoreModulesMap))

    // ok, we have built the main compiler/library/etc. Do we have any nested modules
    // that need to be built? If so, build them now.
    val (preCrossArtifactsMap, repeatableProjectBuilds) = (ec.modules.toSeq flatMap { build =>
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
        ((p.name, artifactsOut), repeatableProjectBuild)
      }
    }).unzip

    // excellent, we now have in preCrossArtifactsMap a sequence of BuildArtifactsOut from the modules,
    // and in scalaArtifactsOut the BuildArtifactsOut from the core. We just need to mix them
    // together, while rewiring the various poms together so that they all refer to one another.
    //
    // ------
    //
    // The scala artifacts are already deployed via the "deploy.local" ant task to the localRepo dir.
    // now, we retrieve the artifacts' modules (they have already been published)
    val uuids = repeatableProjectBuilds map { _.uuid }
    log.info("Retrieving module artifacts")
    log.debug("into " + localRepo)
    val artifactLocations = LocalRepoHelper.getArtifactsFromUUIDs(log.info, localBuildRunner.repository, localRepo, uuids)

    // ------
    // ok. At this point, we have:
    // preCrossArtifactsMap: map name -> artifacts, from the nested modules. Each mapping corresponds to a real nested project,
    //   and the list of artifacts may contain multiple subprojects, each with their own BuildSubArtifactsOut
    // scalaCoreModulesMap: map name -> artifacts. Each mapping is a module generated by one of the scala core projects;
    //   the are cross-versioned. Each mapping contains one BuildArtifactsOut, each of which contains just one BuildSubArtifactsOut
    // scalaArtifactsCore: the non cross-versioned parts of the core, compiled by the ant task
    //
    // ------
    //
    // Before rearranging the poms, we may need to adapt the cross-version strings in the module
    // names. That depends on the value of cross-version in our main build.options.cross-version.
    // If it is "disabled" (default), the modules already have a version without a cross-version
    // string, so we are good. If it "full", the modules will have a cross suffix like
    // "_2.11.0-M5"; we should replace that with the full Scala version string we have now.
    // For "standard" it may be either "_2.11.0-M5" or "_2.11", depending on what the modules
    // decides. For binaryFull, it will be "_2.11" even for milestones.
    // The cross suffix for modules depends on their own build.options.
    // 
    // We change that in conformance to project.crossVersion, so that:
    // - disabled => no suffix
    // - full => full version string
    // - binaryFull => binaryScalaVersion
    // - standard => binary if stable, full otherwise
    // For "standard" we rely on the simple 0.12 algorithm (contains "-"), as opposed to the
    // algorithms detailed in sbt's pull request #600.
    //
    // We have to patch both the list of BuildSubArtifactsOut, as well as the actual filenames
    // (including checksums, if any)

    val Part = """(\d+\.\d+)(?:\..+)?""".r
    def binary(s: String) = s match {
      case Part(z) => z
      case _ => sys.error("Fatal: cannot extract Scala binary version from string \"" + s + "\"")
    }
    val crossSuff = project.buildOptions.crossVersion match {
      case "disabled" => ""
      case "full" => "_" + version
      case "binary" => "_" + binary(version)
      case "standard" => "_" + (if (version.contains('-')) version else binary(version))
      case cv => sys.error("Fatal: unrecognized cross-version option \"" + cv + "\"")
    }
    def patchName(s: String) = fixName(s) + crossSuff

    // we glue to preCrossArtifactsMap (coming from real nested modules), the artifacts
    // that correspond to modules, but originate from the ant task, then proceed with
    // the renaming.
    val artifactsMap = (preCrossArtifactsMap ++ scalaCoreModulesMap) map {
      case (projName, BuildArtifactsOut(subs)) => (projName, BuildArtifactsOut(
        subs map {
          case BuildSubArtifactsOut(name, artifacts, shas) =>
            val renamedArtifacts = artifacts map {
              _.copy(crossSuffix = crossSuff)
            }
            val newSHAs = shas map { sha =>
              val OrgNameVerFilenamesuffix = """(.*)/([^/]*)/([^/]*)/\2(-[^/]*)""".r
              val oldLocation = sha.location
              try {
                val OrgNameVerFilenamesuffix(org, oldName, ver, suffix) = oldLocation
                val newName = patchName(oldName)
                if (newName == oldName) sha else {
                  val newLocation = org + "/" + newName + "/" + ver + "/" + (newName + suffix)
                  def fileDir(name: String) = org.split('/').foldLeft(localRepo)(_ / _) / name / ver
                  def fileLoc(name: String) = fileDir(name) / (name + suffix)
                  val oldFile = fileLoc(oldName)
                  val newFile = fileLoc(newName)
                  fileDir(newName).mkdirs() // ignore if already present
                  if (!oldFile.renameTo(newFile))
                    log.error("cannot rename " + oldLocation + " to " + newLocation + ". Continuing...")
                  sha.copy(location = newLocation)
                }
              } catch {
                case e: _root_.scala.MatchError =>
                  log.error("Path cannot be parsed: " + oldLocation + ". Continuing...")
                  sha
              }
            }
            BuildSubArtifactsOut(name, renamedArtifacts, newSHAs)
        }))
    }
    // includes the cross-versioned modules from the ant core
    val moduleArtifacts = artifactsMap.map { _._2 }

    //
    // we have all our artifacts ready. Time to rewrite the POMs!
    // Note that we will also have to recalculate the shas
    //
    // Let's collect the list of available artifacts:
    //
    val allArtifactsOut = moduleArtifacts :+ scalaCore
    val available = allArtifactsOut.flatMap { _.results }.flatMap { _.artifacts }

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
            m2
          } getOrElse m
        }).asJava
        val newModel = model.clone
        // has the artifactId (aka the name) changed? If so, patch that as well.
        val NameExtractor = """.*/([^/]*)/([^/]*)/\1-[^/]*.pom""".r
        val NameExtractor(newArtifactId, _) = pom.getCanonicalPath()
        newModel.setArtifactId(newArtifactId)
        newModel.setDependencies(newDeps)
        // we overwrite in place, there should be no adverse effect at this point
        val writer = new MavenXpp3Writer
        writer.write(new _root_.java.io.FileWriter(pom), newModel)
        // It's not over, yet. we also have to change the .sha1 and .md5 files
        // corresponding to this pom, if they exist, otherwise artifactory and ivy
        // will refuse to use the pom in question.
        Seq("md5", "sha1") foreach { algorithm =>
          val checksumFile = new File(pom.getCanonicalPath + "." + algorithm)
          if (checksumFile.exists) {
            FileUtils.writeStringToFile(checksumFile, ChecksumHelper.computeAsString(pom, algorithm))
          }
        }
    }

    // dbuild SHAs must be re-computed (since the POMs changed), and the ArtifactsOuts must be merged
    //
    // we already have the list of the new (renamed) artifacts for everything except the core.
    // So, let's rescan and get that out
    val (newScalaCore, _) = getScalaArtifactsOut()

    val out = BuildArtifactsOut(newScalaCore.results ++ artifactsMap.map {
      case (project, arts) =>
        val modArtLocs = arts.results.flatMap { _.artifacts }
        BuildSubArtifactsOut(project, modArtLocs, projSHAs(modArtLocs.map { _.info }, crossSuff))
    })
    log.debug("out: " + writeValue(out))
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
