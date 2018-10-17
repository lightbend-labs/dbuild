package com.typesafe.dbuild.support.scala

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.utils.TrackedProcessBuilder
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import _root_.java.io.File
import com.typesafe.dbuild.adapter.Adapter
import Adapter.Path._
import Adapter.{IO,NameFilter,allPaths,toFF}
import Adapter.IO.relativize
import Adapter.syntaxio._
import com.typesafe.dbuild.logging.Logger
import sys.process._
import com.typesafe.dbuild.repo.core.LocalRepoHelper
import com.typesafe.dbuild.model.Utils.{ writeValue, readValue }
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import collection.JavaConverters._
import org.apache.maven.model.{ Model, Dependency }
import org.apache.maven.model.io.xpp3.{ MavenXpp3Reader, MavenXpp3Writer }
import org.apache.maven.model.Dependency
import org.apache.ivy.util.ChecksumHelper
import com.typesafe.dbuild.support.NameFixer.fixName
import com.typesafe.dbuild.project.build.BuildDirs.dbuildDirName
import com.typesafe.dbuild.model.SeqStringH._

/** Implementation of the Scala  build system. */
object ScalaBuildSystem extends BuildSystemCore {
  val name: String = "scala"
  type ExtraType = ScalaExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => ScalaExtraConfig(None, None, None, Seq.empty, Seq.empty) // pick default values
    case Some(ec: ScalaExtraConfig) => ec
    case _ => throw new Exception("Internal error: scala build config options have the wrong type. Please report")
  }

  def extractDependencies(config: ExtractionConfig, tracker: TrackedProcessBuilder, dir: File,
      extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val ec = config.extra[ExtraType]
    // for the root (main Scala project):
    val meta = readMeta(dir, ec.exclude, log)
    val configAndExtracted = ProjectConfigAndExtracted(config.buildConfig, meta) // use the original config

    // do we happen to have duplicate subprojects?
    val subProjects = meta.subproj
    if (subProjects != subProjects.distinct) {
      sys.error(subProjects.diff(subProjects.distinct).distinct.mkString("These subproject names appear twice: ", ", ", ""))
    }

    // do we have a duplication in provided artifacts?
    val artiSeq = configAndExtracted.extracted.projects.map(art => art.organization + "#" + art.name)
    if (artiSeq.distinct != artiSeq) {
      sys.error(artiSeq.diff(artiSeq.distinct).distinct.mkString("These artifacts are provided twice: ", ", ", ""))
    }

    // ok, now we just have to merge everything together.
    val newMeta = ExtractedBuildMetaH(meta.version, configAndExtracted.extracted.projects, meta.subproj)
    log.info(newMeta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    newMeta
  }

  // runBuild() is called with the Scala source code already in place (resolve() called on the root project),
  // but not for the submodules. After building the core, we will call localBuildRunner.checkCacheThenBuild() on each module,
  // which will in turn resolve it and then build it (if not already in cache).
  def runBuild(project: RepeatableProjectBuild, tracker: TrackedProcessBuilder, dir: File,
    input: BuildInput, localBuildRunner: LocalBuildRunner, buildData: BuildData): BuildArtifactsOut = {
    val ec = project.extra[ExtraType]
    val log = buildData.log

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
    val localRepo = input.outRepo

    // If the ant build file contains the target "publish.local", then only run that
    // target (there is no separate deploy); if the target is not present, then run
    // "distpack-maven-opt", followed by a separate "deploy.local".
    // That can be overridden by specifying a list "targets" in the extra config.
    val hasPublishLocal = antHasTarget("publish.local", dir)

    // Let's see if we can fix up the compiler used to compile this compiler.
    // Were we able to rematerialize a previous scala compiler in our input repo?
    // (TODO: consolidate the method below with the similar method in DBuildRunner)
    def findArtifact(arts: Seq[ArtifactLocation],
      name: String, org: String): Option[ArtifactLocation] =
      (for {
        artifact <- arts.view
        dep = artifact.info
        if dep.organization == name
        if dep.name == org
      } yield artifact).headOption

    def getVersion(art: Option[ArtifactLocation]) = art map { _.version }

    def findVersion(arts: Seq[ArtifactLocation],
      name: String, org: String): Option[String] =
      getVersion(findArtifact(arts, name, org))

    val debugOptions = if (buildData.debug) Seq("-debug") else Seq.empty

    val rewireOptions = if (hasPublishLocal) {

      val customScalaVersion = findVersion(input.artifacts.artifacts, "org.scala-lang", "scala-library")
      // "starr.version" currently also applies to scala-compiler and scala-reflect

      val Part = """(\d+\.\d+)(?:\..+)?""".r
      def binary(s: String) = s match {
        case Part(z) => z
        case _ => sys.error("Fatal: cannot extract Scala binary version from string \"" + s + "\"")
      }

      val scalaRewireOptions: Seq[String] = customScalaVersion.toSeq flatMap { sv =>
        log.info("*** Will compile using the Scala compiler version \"" + sv + "\"" + {
          project.config.space map (" (from space \"" + _.from + "\")") getOrElse ""
        })
        def setScalaBinProp(ver: String) = Seq("-Dscala.binary.version=\"" + ver + "\"")
        val setScalaBin = project.config.getCrossVersionHead match {
          case "full" => setScalaBinProp(version)
          case "binary" => setScalaBinProp(binary(version))
          // If "standard", it will pick scala.binary.version from the properties file
          case "standard" =>
            log.warn("The cross-version level is set to \"standard\". That will cause the resulting cross-version suffix to " +
              "be picked from the scala.binary.version property in the version.properties file of the source tree. That " +
              "suffix (aligned with the original version " + sv + ") may be incompatible with your selected version " + version)
            Seq[String]()
          case level => sys.error("Requested cross-version level \"" + level +
            "\" unsupported in the Scala build system; please use either full, binary, or standard.")
        }
        //    ... set the repo ptr...
        Seq("-Dextra.repo.url=\"file://" + input.artifacts.localRepo.getCanonicalPath + "\"",
          //    ... and the version, change starr.version, as in:
          //      https://github.com/scala/scala/blob/master/versions.properties
          "-Dstarr.version=\"" + sv + "\"") ++ setScalaBin
      }

      val moduleData = Seq(
        // org, name, -Dxxx.version.number and -Dxxx.cross.suffix
        ("org.scala-lang.modules", "scala-xml", "scala-xml"),
        ("org.scala-lang.modules", "scala-parser-combinators", "scala-parser-combinators"),
        ("org.scala-lang.modules", "scala-partest", "partest"),
        ("org.scalacheck", "scalacheck", "scalacheck"),
        ("org.scala-lang.plugins", "scala-continuations-plugin", "scala-continuations-plugin"),
        ("org.scala-lang.plugins", "scala-continuations-library", "scala-continuations-library"),
        ("org.scala-lang.modules", "scala-swing", "scala-swing"),
        ("com.typesafe.akka", "akka-actor", "akka-actor"),
        ("org.scala-lang", "scala-actors-migration", "actors-migration"))

      val extraRewireOptions = (for {
        (org, name, prop) <- moduleData
        art <- findArtifact(input.artifacts.artifacts, org, name)
      } yield {
        val ver = art.version
        log.info("Setting " + name + " to ver = \"" + ver + "\", suffix = \"" + art.crossSuffix + "\"")
        Seq("-D" + prop + ".version.number=\"" + ver + "\"",
          "-D" + prop + ".cross.suffix=\"" + art.crossSuffix + "\"")
      }).flatten

      scalaRewireOptions ++ extraRewireOptions

    } else Seq.empty

    // The ant build script calls maven, meaning that if we use strange version numbers
    // for the artifacts they 1) pollute ~/.m2 and 2) once one is in the cache, all hope to get it evicted is lost
    // Therefore: we use a local .m2 cache for each build, to avoid pollution & collisions
    val dbuildDir = dir / dbuildDirName
    // See the special use of _JAVA_OPTIONS, below
    val localM2repo = dbuildDir / ".m2" / "repository"
    localM2repo.mkdirs()

    if (ec.buildTarget.nonEmpty || ec.deployTarget.nonEmpty)
      sys.error("The extra options \"build-target\" and \"deploy-target\" have been replaced by the new option \"targets\" (see docs).")
    val targets = if (ec.targets.nonEmpty)
      ec.targets
    else if (hasPublishLocal)
      Seq(("publish.local", "."))
    else
      Seq(("distpack-maven", "."), ("deploy.local", "dists/maven/latest"))
    targets foreach {
      case (target, path) =>
        // erase the content of the private cache before each stage of a build, just in case.
        IO.delete(localM2repo.*(toFF("*")).get)
        val targetDir = path.split("/").foldLeft(dir)(_ / _)
        tracker.!(Process(Seq("ant", target,
          "-Dlocal.snapshot.repository=" + localRepo.getAbsolutePath,
          "-Dlocal.release.repository=" + localRepo.getAbsolutePath,
          "-Dmaven.version.number=" + version) ++ rewireOptions ++
          debugOptions ++ ec.buildOptions, Some(targetDir),
          // The special and rather unsupported env variable "_JAVA_OPTIONS" is the *only* thing
          // capable to forcibly change user.home in all the jvm invocations therein. Changing only
          // HOME or defining -Duser.home when calling ant, will *still* cause ~/.m2 to be used by ant & maven
          "_JAVA_OPTIONS" -> ("-Duser.home=\"" + dbuildDir.getCanonicalPath() + "\"")), log) match {
            case 0 => ()
            case n => sys.error("Could not run scala ant build, error code: " + n)
          }
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
      val nameFilter = (toFF(name)) | (toFF((name + "_*")))
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
        allPaths(d).get.filterNot(file => file.isDirectory).map { f =>
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
      artifacts.map(artifactDir(localRepo, _, crossSuffix)).distinct.flatMap { allPaths(_).get }.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml").map(f)
    }

    def projSHAs(artifacts: Seq[ProjectRef], crossSuffix: String): Seq[ArtifactSha] = scanFiles(artifacts, crossSuffix) {
      LocalRepoHelper.makeArtifactSha(_, localRepo)
    }

    // Scala artifacts used to be always with a plain version number. However, now some modules may be built
    // as part of the core; they should use the suffix "org.scala-lang.modules". To complicate matters,
    // the suffix may not necessarily match the scala version we are trying to build.
    // Therefore we need to inspect the files (we try to locate the poms), and determine the proper cross
    // suffix for each.
    def getScalaArtifactsOut() =
      BuildArtifactsOut(meta.projects map {
        proj =>
          val (cross, ver) = findCrossAndVer(localRepo, proj.organization, proj.name)
          // The "None" in ArtifactLocation is the set of extraAttributes, which is only relevant in case
          // the artifact is an sbt plugin; we do not expect the Scala compiler to produce sbt plugins.
          BuildSubArtifactsOut(proj.name, proj.artifacts map { ArtifactLocation(_, ver, cross, pluginAttrs = None) },
            projSHAs(proj.artifacts, cross),
            com.typesafe.dbuild.manifest.ModuleInfo(organization = proj.organization,
              name = proj.name, version = version, com.typesafe.dbuild.manifest.ModuleAttributes(None, None))
          )
      })

    val out = getScalaArtifactsOut()
    log.debug("out: " + writeValue(out))
    out
  }

  /**
   * Read ExtractedBuildMeta from dbuild-meta.json if present in the scala checkout.
   * Otherwise, fall back to hard-coded defaults.
   * Also, exclude any subprojects listed in the "exclude" list; they will still
   * be listed, but will not be published or advertised as available.
   */
  private def readMeta(baseDir: File, exclude: Seq[String], log: Logger): ExtractedBuildMeta = {
    val dbuildMetaFile = new File(baseDir, "dbuild-meta.json")
    val readMeta = try ExtractedBuildMeta(Seq(readValue[ProjMeta](dbuildMetaFile)))
    catch {
      case e: Exception =>
        log.error("Failed to read scala metadata file: " + dbuildMetaFile.getAbsolutePath)
        Logger.prepareLogMsg(log, e)
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
    val readMetaInfo = readMeta.projInfo.headOption getOrElse sys.error("Internal error: readMeta had no projInfo")
    val metaInfo = if (exclude.nonEmpty) {
      val notFound = exclude.diff(allSubProjects)
      if (notFound.nonEmpty) log.warn(notFound.mkString("These subprojects were not found in scala: ", ", ", ""))
      val subProjects = allSubProjects.diff(exclude)
      // Note: Here subProjects contains a list of subprojects in the order
      // in which the names appear in the list of meta "projects". This does not
      // strictly complies with the ExtractedBuildMeta spec, which says that
      // subprojects should be listed in build order. However, the point is moot
      // considering that ant always build everything; the "subproj" list is only
      // used here to decide what to publish to the dbuild repo at the end of
      // the compilation.
      readMetaInfo.copy(subproj = subProjects).copy(projects = readMeta.projects.filter {
        p => subProjects.contains(p.name)
      })
    } else readMetaInfo.copy(subproj = allSubProjects)
    // override the "dbuild.json" version with the one from "build.number" (if it exists)
    val metaInfo2 = readBuildNumberFile(baseDir) map { v => metaInfo.copy(version = v) } getOrElse metaInfo
    // re-package the projInfo inside an ExtractedBuildMeta
    val meta = readMeta.copy(projInfo = Seq(metaInfo2))
    meta
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

  def antHasTarget(target: String, dir: File) =
    Process("ant -p", dir).lines.exists(_.startsWith(" " + target + "  "))

  /** Read version from build.number but fake the rest of the ExtractedBuildMeta.*/
  private def fallbackMeta(baseDir: File): ExtractedBuildMeta = {

    val version = readBuildNumberFile(baseDir) getOrElse sys.error("unable to load scala version number!")

    def detectActorsMigration(baseDir: File) = {
      val dir1 = baseDir / "src" / "actors-migration" / "scala" / "actors"
      (dir1 / "Pattern.scala").isFile || (dir1 / "migration" / "Pattern.scala").isFile
    }

    // hard-coded
    ExtractedBuildMeta(Seq(ProjMeta(version, Seq(
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
      else Seq.empty))))
  }
}
