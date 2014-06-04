package com.typesafe.dbuild

import sbt._
import distributed.project.model
import distributed.support.sbt.SbtBuildConfig
import distributed.project.model.ArtifactLocation
import distributed.project.model.ArtifactSha
import distributed.project.model.Utils.{ writeValue, readValue }
import StateHelpers._
import DependencyAnalysis.{ normalizedProjectNames, normalizedProjectName }
import DistributedBuildKeys._
import distributed.support.NameFixer.fixName
import java.io.File
import distributed.repo.core.LocalArtifactMissingException
import org.apache.ivy.core.module.id.ModuleRevisionId
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.BuildSubArtifactsOut
import distributed.project.model.SavedConfiguration
import distributed.project.model.BuildArtifactsInMulti
import distributed.project.build.BuildDirs._
import distributed.project.model.BuildInput
import distributed.support.sbt.SbtRunner.{ sbtIvyCache, rewireInputFile, buildArtsFile, genArtsInputFile }
import distributed.support.sbt.{ RewireInput, GenerateArtifactsInput }
import distributed.support.SbtUtil.{ pluginAttrs, fixAttrs }
import distributed.project.model.SbtPluginAttrs

object DistributedRunner {

  val scalaOrgs = Seq("org.scala-lang", "org.scala-lang.modules", "org.scala-lang.plugins")

  def timed[A](f: => Stated[A]): Stated[Long] = {
    val start = java.lang.System.currentTimeMillis
    val result = f
    val end = java.lang.System.currentTimeMillis
    result map (_ => (end - start))
  }

  def averageOf[A](n: Int)(state: Stated[A])(f: Stated[Long] => Stated[Long]): (Stated[Double]) = {
    val result = (state.of(0L) /: (0 to n).toSeq) { (state, _) =>
      val prev = state.value
      timed(f(state)) map (_ + prev)
    }
    result map (_ / n.toDouble)
  }

  // Pending weak references may lock in place classloaders,
  // leading to tens of thousands of unfinalized file descriptors,
  // and >1GB of PermGen in use.
  // purge() will force finalization to run for FileInputStreams and
  // the like that are no longer used, allowing them to be really
  // reclaimed by GC, which in turn frees the classloaders, and
  // consequently also the corresponding space in PermGen. Phew!
  private def purge() {
    System.gc() // finalizable objects that are unreachable are passed to the finalizer thread
    java.lang.System.runFinalization() // finalization is forced for all those objects
    System.gc() // those finalized objects are deallocated for good
  }

  /** Runs a series of commands across projects, aggregating results. */
  private def runAggregate[Q, T](state: State, projects: Seq[String], init: Q)(merge: (Q, T) => Q)(f: (ProjectRef, State, Q) => (State, T)): (State, Q) = {
    val extracted = Project.extract(state)
    import extracted._
    val Some(baseDirectory) = Keys.baseDirectory in ThisBuild get structure.data
    val refs = getProjectRefs(extracted)
    // I need a subset of refs, a sequence in the order specified by "project"
    val newRefs = getSortedProjects(projects, refs, baseDirectory)
    newRefs.foldLeft[(State, Q)](state -> init) {
      case ((state, current), ref) =>
        val (state2, next) =
          f(ref, state, current)
        val merged = merge(current, next)
        state2 -> merged
    }
  }

  // verify that the requested projects in SbtBuildConfig actually exist
  def verifySubProjects(requestedProjects: Seq[String], refs: Seq[sbt.ProjectRef], baseDirectory: File): Unit = {
    if (requestedProjects.nonEmpty) {
      val uniq = requestedProjects.distinct
      if (uniq.size != requestedProjects.size) {
        sys.error("Some subprojects are listed twice: " + (requestedProjects.diff(uniq)).mkString("\"", "\", \"", "\"."))
      }
      val availableProjects = normalizedProjectNames(refs, baseDirectory)
      val notAvailable = requestedProjects.toSet -- availableProjects
      if (notAvailable.nonEmpty)
        sys.error("These subprojects were not found: " + notAvailable.mkString("\"", "\", \"", "\". ") +
          " Found: " + availableProjects.mkString("\"", "\", \"", "\". "))
    } else sys.error("Internal error: subproject list is empty")
  }

  def getSortedProjects(projects: Seq[String], refs: Seq[ProjectRef], baseDirectory: File): Seq[ProjectRef] = {
    verifySubProjects(projects, refs, baseDirectory)
    projects map { p => refs.find(ref => (p == normalizedProjectName(ref, baseDirectory))).get }
  }

  def makeBuildResults(artifacts: Seq[BuildSubArtifactsOut], localRepo: File): model.BuildArtifactsOut =
    model.BuildArtifactsOut(artifacts)

  def printResults(outFile: File, artifacts: Seq[BuildSubArtifactsOut], localRepo: File): Unit =
    IO.write(outFile, writeValue(makeBuildResults(artifacts, localRepo)))

  // TODO - Here we rely on a sequence of artifact locations, and we try to do
  // the matching manually. Ideally, we should take our ModuleID, point Ivy to
  // the rematerialized repository, and ask Ivy whether the module can be
  // resolved against that repository. It requires a bit of code, but would
  // be somewhat more general, at least in principle.
  //
  // Note: ArtifactLocation at this point does not (should not) contain any
  // cross version suffix attached to its "name" field; therefore, we apply
  // fixName() only to the ModuleID we are trying to rewrite right now.
  def fixModule(arts: Seq[model.ArtifactLocation], modules: Seq[ModuleRevisionId], crossVersion: String,
    checkMissing: Boolean, fromSpace: String, log: Logger, currentName: String, currentOrg: String)(m: ModuleID): ModuleID = {
    def expandName(a: Artifact) = {
      import a._
      classifier match {
        case None => fixName(name)
        case Some(clas) => fixName(name) + "-" + clas
      }
    }
    def findArt: Option[model.ArtifactLocation] =
      (for {
        artifact <- arts.view
        if artifact.info.organization == m.organization
        if artifact.info.name == fixName(m.name) || (m.explicitArtifacts map expandName).contains(artifact.info.name)
      } yield artifact).headOption
    findArt map { art =>
      val newArt =
        // Note: do not take art.info.name; in case of explicitArtifacts, it will not match (it may have an extra suffix
        // due to the classifier). Use fixName(m.name) instead.
        // Note2: in case of a classifier, there is a hack to make it match even
        // if the requested library has an explicit matching name and no classifier;
        // this is required by one of the test projects. So we match in that way as
        // well (see above m.explicitArtifacts contains...)
        m.copy(name = fixName(m.name) + art.crossSuffix, revision = art.version, crossVersion = CrossVersion.Disabled, explicitArtifacts =
          // More hacking: the explicit artifact did not originally contain the crossSuffix; if we leave it in place as-is, the name in the
          // explicit artifact takes over, and fixName(m.name)+art.crossSuffix gets ignored. Conversely, if we remove the explicit artifact
          // in order to make the name (with crossSuffic) match, we may lose a classifier specified in the initial explicit artifact, with the
          // result of matching against the wrong artifact. The only solution is rewriting the explicitArtifacts adequately.
          m.explicitArtifacts.map { a =>
            if (expandName(a) == art.info.name && a.classifier.nonEmpty)
              // this means, for example, that the explicitArtifact is "compiler-interface" with classifier "bin", while art.info.name is
              // "compiler-interface-bin". This is made more complicated by the crossSuffix, as just adding the suffix would mean the
              // explicitArtifact is "compiler-interface_2.11", classifier bin, therefore "compiler-interface_2.11-bin", while
              // art.info.name is "compiler-interface-bin_2.11". Oops. We fix that by appending the suffix to the /classifier/ instead,
              // in order to make things somehow work.
              a.copy(classifier = Some(fixName(a.classifier.get) + art.crossSuffix))
            else
              a
          },
          extraAttributes = fixAttrs(m.extraAttributes, art.pluginAttrs)
        )
      log.debug("Rewriting " + m + " to " + newArt + " (against: " + art + " )")
      newArt
    } getOrElse {
      // TODO: here I should discover whether there are libDeps of the kind:
      // "a" % "b_someScalaVer" % "ver" (cross disabled, therefore), or
      // other cross-versioned dependencies that we have been unable to fix.
      // That means we either miss a project in our config, or that we
      // explicitly need such a lax resolution in this case
      // (and we should get a warning about that circumstance anyway).
      log.debug("Dependency not rewritten: " + m)
      // Do not inspect the artifacts that belong to the project we are building at this time:
      if (!(modules exists { i => i.getOrganisation == m.organization && fixName(i.getName) == fixName(m.name) })) {
        // Do we have a Scala-based library dependency that was not rewritten? We can recognize it
        // since there is a cross version suffix attached to the name, hence m.name != fixName(m.name)
        if ((m.name != fixName(m.name) || m.crossVersion != CrossVersion.Disabled)) {
          // If we are here, it means that this is a library dependency that is required,
          // that refers to an artifact that is not provided by any project in this build,
          // and that needs a certain Scala version (range) in order to work as intended.
          // We check crossVersion: if it requires the correspondence to be exact, we fail;
          // otherwise we just print a warning and leave Ivy to fail if need be.
          val msg = "**** Missing dependency: the library " + m.organization + "#" + fixName(m.name) +
            " is not provided (in space \"" + fromSpace + "\") by any project in this configuration file."
          crossVersion match {
            case "binaryFull" | "disabled" | "full" if checkMissing =>
              log.error(msg)
              log.error("In order to control which version is used, please add the corresponding project to the build file")
              log.error("(or use \"check-missing:false\" to ignore (not recommended)).")
              sys.error("Required dependency not found")
            case "binaryFull" | "disabled" | "full" | "standard" =>
              if (crossVersion == "standard" && checkMissing)
                log.warn("*** The option \"check-missing\" is ignored when \"cross-version\" is set to \"standard\"")
              log.warn(msg)
              log.warn("The library (and possibly some of its dependencies) will be retrieved from the external repositories.")
              log.warn("In order to control which version is used, you may want to add this dependency to the dbuild configuration file.")
            case _ => sys.error("Unrecognized option \"" + crossVersion + "\" in cross-version")
          }
        } else {
          // in the case of plugins, m.name == fixName(m.name), so we cannot rely on them being different in order to detect
          // the missing dependency. However, we can inspect the extraAttributes to find out if we are dealing with an sbt plugin
          // Note that we may also encounter the dbuild plugin itself, among the dependencies, which we normally ignore
          pluginAttrs(m) map { attrs => // if pluginAttrs(m) is not None, then:
            if (m.name != "distributed-sbt-plugin" && m.organization != "com.typesafe.dbuild") {
              val msg = "This sbt plugin is not provided (in space \"" + fromSpace + "\") by any project within this dbuild config: " + m.organization + "#" + m.name + " (sbtVersion=" + attrs.sbtVersion + ", scalaVersion=" + attrs.scalaVersion + ")"
              if (checkMissing) {
                log.error(msg)
                sys.error("Required dependency not found")
              } else {
                log.warn(msg)
              }
            }
          }
        }
      }
      m
    }
  }

  def inNScopes(n: Int) = if (n == 1) "in one scope" else "in " + n + " scopes"

  def fixPublishTos2(repoDir: File)(oldSettings: Seq[Setting[_]], log: Logger): Seq[Setting[_]] = {
    val name = "deploy-to-local-repo"
    val mavenRepo = Some(Resolver.file(name, repoDir)(Resolver.mavenStylePatterns))
    val ivyRepo = Some(Resolver.file(name, repoDir)(Resolver.ivyStylePatterns))

    // The process is a bit tricky. Consider the following scenario (really occurring in sbt):
    // - publishTo in ThisBuild: (...something... or None!...)
    // - publishMavenStyle in ThisBuild: true
    // - publishMavenStyle in project: false
    //
    // In this case, the project would see the publishTo above, and publishMavenStyle false. Yet, the
    // two are in two different scopes, and it would not be obvious how to fix the settings just by
    // looking at the only publishTo: checking the publishMavenStyle in the same scope would be misleading.
    //
    // I cannot "delete" existing publishTo repositories, unfortunately, therefore I am trying the
    // following approach:
    // 1) find lastSettings by publishTo. Change them according to their own format: PatternBased with
    // isMavenCompatible=false -> Ivy, else Maven. If None, I still go for Maven, at this point.
    // 2) scan according to publishMavenStyle, add a publishTo to each scope accordingly
    // 3) if no publishTo and no publishMavenStyle, add a default Maven publishTo & publishMavenStyle
    // (but there should always be a default publishMavenStyle in sbt)
    //
    // The mess above should do the trick, at least in theory.

    val ptSettings = lastSettingsByScope(oldSettings, Keys.publishTo)
    if (ptSettings.nonEmpty)
      log.info("Updating publishTo repo " + inNScopes(ptSettings.length))

    val newSettings1 = {
      ptSettings map { s =>
        SbtUpdate.update(s.asInstanceOf[Setting[Option[sbt.Resolver]]].key) {
          _ match {
            case Some(r: PatternsBasedRepository) if (!r.patterns.isMavenCompatible) => ivyRepo
            case _ => mavenRepo
          }
        }
      }
    }

    val pmsSettings = lastSettingsByScope(oldSettings ++ newSettings1, Keys.publishMavenStyle)
    if (pmsSettings.nonEmpty)
      log.info("Found publishMavenStyle " + inNScopes(pmsSettings.length) + "; changing publishTo settings accordingly.")

    val newSettings = newSettings1 ++ {
      pmsSettings map { s =>
        val sc = s.key.scope
        Keys.publishTo in sc <<= (Keys.publishMavenStyle in sc) { if (_) mavenRepo else ivyRepo }
      }
    }

    if (newSettings.isEmpty) {
      log.info("No publishTo or publishMavenStyle settings found; adding a default Maven publishTo.")
      Seq(Keys.publishTo in ThisBuild := mavenRepo,
        Keys.publishMavenStyle in ThisBuild := true)
    } else newSettings
  }

  // the "...2" routines generate a list of new settings, typically one per scope, that are tacked at the end of the current
  // ones; the sbt session is also patched accordingly once the full list of additional new settings is known. 

  // fixDependencies2(), for example:
  // Generates a list of additional settings that can be tacked onto the current list of settings in order to
  // adapt dependencies in order to reflect the new values.
  // Note: the "libraryDependencies" setting is usually present in multiple places in the list of settings; each one may
  // modify the list (usually adding further dependencies). Hence, it would be unnecessary to create a new setting patching
  // each occurrence: duplicates and other weirdness may result.
  // We only inspect the most recent setting for each scope, adding a rewriting to that last one. In case "dbuild-setup" is
  // called multiple times, each will patch the previous dbuild one, which is however ok as we replace rather than adding;
  // ideally a "reload" should precede "dbuild-setup", however.
  //

  // Collect the last settings in each scope that (re)define a given key
  private def lastSettingsByScope(oldSettings: Seq[Setting[_]], theKey: Scoped): Seq[Setting[_]] = {
    val key = theKey.key
    oldSettings.filter(_.key.key == key).groupBy(_.key.scope).map(_._2.last).toSeq
  }

  // applies a generic transformation from Setting[K] (the old one) to another Setting[K] (the new one)
  def fixGenericTransform2[K](k: Scoped)(f: Setting[K] => Setting[K])(msg: String)(oldSettings: Seq[Setting[_]], log: Logger) = {
    val lastSettings = lastSettingsByScope(oldSettings, k)
    if (lastSettings.nonEmpty) log.info(msg + " " + inNScopes(lastSettings.length))
    lastSettings.asInstanceOf[Seq[Setting[K]]] map f
  }

  // as above, but assumes the transformation is a simple Project.update (aka: ~= )
  def fixGenericK2[K](k: Scoped, f: K => K) = fixGenericTransform2(k) { s: Setting[K] => SbtUpdate.update(s.key)(f) } _

  // Separate cases for settings and tasks (to keep the type inferencer happy)
  def fixGeneric2[K](k: SettingKey[K], m: String)(f: K => K) = fixGenericK2(k, f)(m)
  def fixGeneric2[K](k: TaskKey[K], m: String)(f: Task[K] => Task[K]) = fixGenericK2(k, f)(m)

  type Fixer = (Seq[Setting[_]], Logger) => Seq[Setting[_]]

  // There are two parts in dealing with cross-versions.
  // 1) Keys.crossVersion will affect how the projects are published
  // 2) Keys.scalaBinaryVersion will impact on how dependencies that are
  //    declared as '%%', with no further CrossVersion qualification,
  //    are resolved.
  // 1) can be: standard, full, disabled (or forcing "binary", which is not very useful in this context)
  // 2) can be: standard (whatever it is in the projects), or full (<<= scalaVersion),
  //    or forced to Binary (again, not very useful).
  //    If a dependency is explicit in the form "a" % "b_someScalaVer" % "ver",
  //    and the corresponding project is not in the dbuild config file, however, I will be
  //    unable to discover that it is missing (unless I do some further postprocessing,
  //    by stripping the suffix and comparing the names).
  // The combinations are:
  // "standard" -> 1 and 2 set to standard (nothing is done)
  // "full" -> 1 and 2 both set to full
  // "disabled" -> 1 set to disabled, 2 set to full
  // "binaryFull" -> 2 set to full, 1 left alone (aka: it will publish full the projects that normally would
  //                 publish as binary; it will publish the others as they are; it will discover missing libs)
  //                 (this is for testing only).
  def fixCrossVersions2(crossVersion: String) = {
    val scalaBinaryFull =
      fixGenericTransform2(Keys.scalaBinaryVersion) { s: Setting[String] =>
        val sc = s.key.scope
        Keys.scalaBinaryVersion in sc <<= Keys.scalaVersion in sc
      }("Setting Scala binary version to full") _

    crossVersion match {
      case "binaryFull" => scalaBinaryFull
      case "standard" => { (_: Seq[Setting[_]], _: Logger) => Seq.empty }
      case "disabled" => { (s: Seq[Setting[_]], l: Logger) =>
        fixGeneric2(Keys.crossVersion, "Disabling cross version") { _ => CrossVersion.Disabled }(s, l) ++
          scalaBinaryFull(s, l)
      }
      case "full" => { (s: Seq[Setting[_]], l: Logger) =>
        fixGeneric2(Keys.crossVersion, "Setting cross version to full") { _ => CrossVersion.full }(s, l) ++
          scalaBinaryFull(s, l)
      }
      case _ => sys.error("Unrecognized option \"" + crossVersion + "\" in cross-version")
    }
  }

  // Altering allDependencies, rather than libraryDependencies, will also affect projectDependencies.
  // This is necessary in case some required inter-project dependencies have been explicitly excluded.
  def fixDependencies2(locs: Seq[model.ArtifactLocation], modules: Seq[ModuleRevisionId], crossVersion: String,
    checkMissing: Boolean, fromSpace: String, log: Logger) =
    fixGenericTransform2(Keys.allDependencies) { r: Setting[Task[Seq[sbt.ModuleID]]] =>
      val sc = r.key.scope
      Keys.allDependencies in sc <<= (Keys.allDependencies in sc, Keys.name in sc, Keys.organization in sc) map { (old, n, o) =>
        old map fixModule(locs, modules, crossVersion, checkMissing, fromSpace, log, n.toLowerCase, o.toLowerCase)
      }
    }("Updating dependencies") _

  def fixVersions2(in: BuildInput) =
    fixGeneric2(Keys.version, "Updating version strings") { _ => in.version }

  def fixResolvers2(dbuildRepoDir: File, log: Logger) =
    fixGeneric2(Keys.fullResolvers, "Adding resolvers to retrieve build artifacts") {
      _ map { old =>
        // make sure to add our resolvers at the beginning!
        log.debug("Appending local-repo resolvers: " + dbuildRepoDir.getAbsolutePath())
        val qq = Seq(
          // IMPORTANT: the mvn-build-local and ivy-build-local names MUST match those
          // used through writeRepoFile() by SbtBuilder.buildSbtProject() (look for the
          // writeRepoFile() call: "build-local" is prefixed by "ivy-" and "mvn-"
          "mvn-build-local" at ("file:" + dbuildRepoDir.getAbsolutePath()),
          Resolver.file("ivy-build-local", dbuildRepoDir)(Resolver.ivyStylePatterns)) ++
          (old filterNot { r =>
            val n = r.name; n == "mvn-build-local" || n == "ivy-build-local"
          })
        log.debug("---------Modified list of resolvers:---------")
        qq foreach { r => log.debug(r.toString) }
        log.debug("---------------------------------------------")
        qq
      }
    }

  // sbt will try to check the scala binary version we use in this project (the full version,
  // including suffixes) against what Ivy reports as the version of the scala library (which is
  // a shortened version). That generates tons of warnings; in order to disable that, we set
  // IvyScala.checkExplicit to false
  def fixScalaBinaryCheck2 =
    fixGeneric2(Keys.ivyScala, "Disabling Scala binary checking") { _ map { _.copy(checkExplicit = false) } }

  // We need to disable the inter-project resolver entirely. Otherwise, sbt will try to build all
  // of the dependent subprojects each time one of the subprojects is built, including some that
  // we may have explicitly excluded (as they are built in a different project, for instance)
  // (currently unused).
  def fixInterProjectResolver2 =
    fixGeneric2(Keys.projectResolver, "Disabling inter-project resolver") { _ map { _ => new RawRepository(new ProjectResolver("inter-project", Map.empty)) } }

  // alternate version, which only removes the artifacts that are not part
  // of the selected subprojects. Might be more suitable for setupcmd; in this case,
  // local-publish-repo should not be added to the list of resolvers.
  def fixInterProjectResolver2bis(modules: Seq[ModuleRevisionId]) =
    fixGenericTransform2(Keys.projectResolver) { r: Setting[Task[Resolver]] =>
      val sc = r.key.scope
      Keys.projectResolver in sc <<= (Keys.projectDescriptors in sc) map {
        m =>
          val k = m.filter {
            case (a, _) => modules exists { b => b.getOrganisation == a.getOrganisation && fixName(b.getName) == fixName(a.getName) }
          }
          new RawRepository(new ProjectResolver("inter-project", k))
      }
    }("Patching the inter-project resolver") _

  // Fix the ivy home location, so that each level has its own separate cache.
  // It is necessary to have one per level, since different levels may resolve
  // from different spaces, and different spaces may contain artifacts that have
  // same org, name, version, but that are actually different. Artifacts that
  // belong to different spaces must never come into contact with each other.
  def fixIvyPaths2(log: Logger) =
    fixGenericTransform2(Keys.baseDirectory) { r: Setting[IvyPaths] =>
      val sc = r.key.scope
      //      log.debug("ivy-paths found in scope " + sc)
      Keys.ivyPaths in sc <<= (Keys.baseDirectory in sc) {
        d =>
          new IvyPaths(d, Some(sbtIvyCache(d)))
      }
    }("Patching Ivy paths") _

  // extract the ModuleRevisionIds of all the subprojects of this dbuild project (as calculated from exclusions, dependencies, etc).
  def getModuleRevisionIds(state: State, projects: Seq[String], log: Logger) =
    runAggregate[Seq[ModuleRevisionId], ModuleRevisionId](state, projects, Seq.empty) { _ :+ _ } {
      (proj, state, _) => (state, Project.extract(state).runTask(Keys.ivyModule in proj, state)._2.dependencyMapping(log)._1)
    }._2

  // In order to convince sbt to use the scala instance we need, we just generate a fictitious
  // "lib" directory, like the one that would be generated by ant dist, and set scalaHome to that
  // directory (see ./util/classpath/ScalaInstance.scala in sbt for details)
  // Java 6 has no symlinks facility, therefore the files need to be copied.
  //
  // repoDir is the local-repo, which should already contain the re-materialized files
  def fixScalaVersion2(dbuildDir: File, repoDir: File, locs: Seq[model.ArtifactLocation])(oldSettings: Seq[Setting[_]], log: Logger) = {
    customScalaVersion(locs).toSeq flatMap { ver =>
      log.info("Preparing Scala binaries: scala-library version " + ver)
      val scalaArts = locs.filter(scalaOrgs contains _.info.organization)
      val scalaHomeSha = hashing sha1 (scalaArts map { _.version })
      val scalaHome = dbuildDir / "scala" / scalaHomeSha
      generateScalaDir(repoDir, scalaArts, scalaHome)
      // I replicate some of the functionality now present in sbt 0.13
      val blacklist: Set[String] = Set("scala-actors.jar", "scalacheck.jar", "scala-partest.jar", "scala-partest-javaagent.jar", "scalap.jar", "scala-swing.jar")
      def scalaLib(scalaHome: File): File = new File(scalaHome, "lib")
      def allJars(scalaHome: File): Seq[File] = IO.listFiles(scalaLib(scalaHome)).filter(f => !blacklist(f.getName))
      def scalaJar(scalaHome: File, name: String) = new File(scalaLib(scalaHome), name)
      def compilerJar(scalaHome: File) = scalaJar(scalaHome, "scala-compiler.jar")
      def libraryJar(scalaHome: File) = scalaJar(scalaHome, "scala-library.jar")
      //
      fixGeneric2(Keys.scalaVersion, "Setting Scala version to: " + ver) { _ => ver }(oldSettings, log) ++
        fixGenericTransform2(Keys.scalaInstance) { s: Setting[Task[ScalaInstance]] =>
          val sc = s.key.scope
          Keys.scalaInstance in sc <<= Keys.appConfiguration in sc map { app =>
            val launcher = app.provider.scalaProvider.launcher
            ScalaInstance(libraryJar(scalaHome), compilerJar(scalaHome), launcher, allJars(scalaHome): _*)
          }
        }("Setting Scala instance")(oldSettings, log)
    }
  }

  // get the custom scala version string, if one is present somewhere in the list of artifacts of this build  
  // if not, we'll skip the scala home setup
  private def customScalaVersion(arts: Seq[distributed.project.model.ArtifactLocation]): Option[String] =
    (for {
      artifact <- arts.view
      dep = artifact.info
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
    } yield artifact.version).headOption

  private def generateScalaDir(repoDir: File, scalaArts: Seq[ArtifactLocation], scalaHome: File): Unit = {
    scalaArts foreach { art =>
      val org = art.info.organization
      val name = art.info.name + art.crossSuffix
      val ver = art.version
      val mavenFile = mavenJarFile(repoDir, org, name, ver)
      val ivyFile = ivyJarFile(repoDir, org, name, ver)
      if (mavenFile.isFile && ivyFile.isFile)
        sys.error("Unexpected internal error: both maven and ivy provide the artifact " +
          art.info.name + ". Please report.")
      if (!mavenFile.isFile && !ivyFile.isFile)
        sys.error("Unexpected error: no artifact file found, for the artifact " +
          art.info.name + ". Please report.")
      if (mavenFile.isFile)
        retrieveJarFile(mavenFile, scalaHome, name)
      if (ivyFile.isFile)
        retrieveJarFile(ivyFile, scalaHome, name)
    }
  }

  def mavenJarFile(repoDir: File, org: String, name: String, version: String) =
    mavenBase(repoDir, org) / name / version / (name + "-" + version + ".jar")
  def ivyJarFile(repoDir: File, org: String, name: String, version: String) =
    ivyBase(repoDir, org) / name / version / "jars" / (name + ".jar")

  def mavenBase(repoDir: File, org: String) = org.split('.').foldLeft(repoDir)(_ / _)
  def ivyBase(repoDir: File, org: String) = repoDir / org

  def retrieveJarFile(file: File, scalaHome: File, name: String) = {
    try IO.copyFile(file, scalaHome / "lib" / (name + ".jar"), false)
    catch {
      case e: Exception =>
        throw new LocalArtifactMissingException("Unexpected internal error while copying " + name + ". Please report.")
    }
  }

  def fixPGPs2(oldSettings: Seq[Setting[_]], log: Logger) =
    fixGeneric2(Keys.skip, "Disabling PGP signing") { old => old map (_ => true) }(oldSettings.filter {
      _.key.scope.task.toOption match {
        case Some(scope) if scope.label.toString == "pgp-signer" => true
        case _ => false
      }
    }, log)

  //  def fixBuildSettings(config: SbtBuildConfig, state: State): State = {
  //    // TODO: replace with the correct logger
  //    val log = sbt.ConsoleLogger()
  //    log.info("Updating dependencies...")
  //    val extracted = Project.extract(state)
  //    import extracted._
  //    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / dbuildDirName)
  //
  //    dbuildDirectory map { dbuildDir =>
  //      val repoDir = dbuildDir / inArtsDirName
  //
  //      val refs = getProjectRefs(extracted)
  //
  //      // config.info.subproj is the list of projects calculated in DependencyAnalysis;
  //      // conversely, config.config.projects is the list specified in the
  //      // configuration file (in the "extra" section)
  //      val modules = getModuleRevisionIds(state, config.info.subproj.head /* TODO FIX */ , log)
  //
  //      def newSettings(oldSettings: Seq[Setting[_]]) =
  //        preparePublishSettings(config, log, oldSettings) ++
  //          prepareCompileSettings(log, modules, dbuildDir, repoDir, config.info.artifacts.artifacts,
  //            oldSettings, config.crossVersion)
  //
  //      newState(state, extracted, newSettings)
  //
  //    } getOrElse {
  //      sys.error("Key baseDirectory is undefined in ThisBuild: aborting.")
  //    }
  //  }

  def printPR(state: State): Unit = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(extracted)
    refs foreach { ref =>
      val resolver = extracted.runTask(Keys.projectResolver in ref, state)._2
      println("\tprojectResolver in %s: (%s)" format (ref.project, resolver))
    }
  }

  def printResolvers(state: State): Unit = {
    println("Using resolvers:")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(extracted)
    for {
      ref <- refs
      (_, resolvers) = extracted.runTask(Keys.fullResolvers in ref, state)
      r <- resolvers
    } println("\t(%s) - %s" format (r.name, r.toString))
  }

  // buildStuff() works by folding over the list of subprojects a sequence of operations: building,
  // testing, publishing, and file extraction.
  // The folding passes around the following: (Seq[File],Seq[(String,ArtifactMap,Seq[String])]),
  // where the first Seq[File] is the set of files present in the publishing repository,
  // and the second Seq associates the subproject name with the list of artifacts, as well as
  // the list of shas of the files published to the repository during this step.
  def buildStuff(state2: State, resultFile: File, config: GenerateArtifactsInput): State = {

    // we shall no longer rewire from within buildStuff().
    // That implies that "dbuild-build" must rewire explicitly before calling buildStuff
    // val state2 = fixBuildSettings(config, state)
    //    printResolvers(state2)
    //    printPR(state)
    //    printPR(state2)

    val refs = getProjectRefs(Project.extract(state2))
    val Some(baseDirectory) = Keys.baseDirectory in ThisBuild get Project.extract(state2).structure.data

    def timedBuildProject(ref: ProjectRef, state: State): (State, ArtifactMap) = {
      println("Running timed build: " + normalizedProjectName(ref, baseDirectory))
      val x = Stated(state)
      def cleanBuild(state: Stated[_]) = {
        val cleaned = state runTask Keys.clean
        timed(cleaned runTask (Keys.compile in Compile))
      }
      val perf = averageOf(10)(x)(cleanBuild)
      val y = perf.runTask(extractArtifacts in ref)
      val arts = y.value map (_.copy(buildTime = perf.value))
      (y.state, arts)
    }
    def untimedBuildProject(ref: ProjectRef, state: State): (State, ArtifactMap) = {
      println("Running build: " + normalizedProjectName(ref, baseDirectory))
      val y = Stated(state).runTask(extractArtifacts in ref)
      (y.state, y.value)
    }

    println(config.info.subproj.head.mkString("These subprojects will be built: ", ", ", ""))
    val buildAggregate = runAggregate[(Seq[File], Seq[BuildSubArtifactsOut]), (Seq[File], BuildSubArtifactsOut)](state2, config.info.subproj.head, (Seq.empty, Seq.empty)) {
      case ((oldFiles, oldArts), (newFiles, arts)) => (newFiles, oldArts :+ arts)
    } _

    // If we're measuring, run the build several times.
    val buildTask = if (config.measurePerformance) timedBuildProject _ else untimedBuildProject _

    def buildTestPublish(ref: ProjectRef, state6: State, previous: (Seq[File], Seq[BuildSubArtifactsOut])): (State, (Seq[File], BuildSubArtifactsOut)) = {
      println("----------------------")
      println("Processing subproject: " + normalizedProjectName(ref, baseDirectory))
      val (_, libDeps) = Project.extract(state6).runTask(Keys.allDependencies in ref, state6)
      println("All Dependencies for subproject " + normalizedProjectName(ref, baseDirectory) + ":")
      libDeps foreach { m => println("   " + m) }
      //      val (_,pRes) = Project.extract(state6).runTask(Keys.projectResolver in ref, state)
      //      println("Project Resolver for project "+ref.project+":")
      //      println("   "+pRes)

      val (state7, artifacts) = buildTask(ref, state6)
      purge()

      val state8 = if (config.runTests) {
        println("Testing: " + normalizedProjectName(ref, baseDirectory))
        Project.extract(state7).runTask(Keys.test in (ref, Test), state7)._1
      } else state7
      purge()

      println("Publishing: " + normalizedProjectName(ref, baseDirectory))
      val (state9, _) =
        Project.extract(state8).runTask(Keys.publish in ref, state8)
      purge()

      // We extract the set of files published during this step by checking the
      // current set of files in the repository against the files we had previously
      val previousFiles = previous._1
      val localRepo = config.info.outRepo.getAbsoluteFile
      val currentFiles = (localRepo.***).get.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml")
      val newFilesShas = currentFiles.diff(previousFiles).map { LocalRepoHelper.makeArtifactSha(_, localRepo) }

      (state9, (currentFiles, BuildSubArtifactsOut(normalizedProjectName(ref, baseDirectory), artifacts, newFilesShas)))
    }

    val (state3, (files, artifactsAndFiles)) = buildAggregate(buildTestPublish)

    printResults(resultFile, artifactsAndFiles, config.info.outRepo)
    state3
  }

  def loadBuildArtifacts(localRepos: Seq /*Levels*/ [File], builduuid: String, thisProject: String, log: Logger) = {
    import distributed.repo.core._
    val cache = Repository.default
    val project = findRepeatableProjectBuild(builduuid, thisProject, log)
    log.info("Retrieving dependencies for " + project.uuid + " " + project.config.name)
    val uuids = project.depInfo map { _.dependencyUUIDs }
    val fromSpaces = project.configAndExtracted.getSpace.fromStream // one per uuidGroup
    val BuildArtifactsInMulti(artifacts) = LocalRepoHelper.getArtifactsFromUUIDs(log.info, cache, localRepos, uuids, fromSpaces)
    (project, artifacts)
  }

  def findRepeatableProjectBuild(builduuid: String, thisProject: String, log: Logger) = {
    import distributed.repo.core._
    log.info("Finding information for project " + thisProject + " in build " + builduuid)
    val cache = Repository.default
    val projects = (for {
      SavedConfiguration(expandedDBuildConfig, build) <- LocalRepoHelper.readBuildMeta(builduuid, cache).toSeq
      allProjects = build.repeatableBuilds
      project <- allProjects.filter(_.config.name == thisProject)
    } yield project) // we know project names are unique
    if (projects.isEmpty) sys.error("There is no project named " + thisProject + " in build " + builduuid)
    if (projects.size > 1) sys.error("Unexpected internal error; found multiple projects named " + thisProject + " in build " + builduuid)
    projects.head
  }

  private def prepareCompileSettings(log: ConsoleLogger, modules: Seq[ModuleRevisionId], dbuildDir: File,
    repoDir: File, arts: Seq[ArtifactLocation], oldSettings: Seq[Setting[_]], crossVersion: String,
    checkMissing: Boolean, fromSpace: String) = {
    Seq[Fixer](
      fixResolvers2(repoDir, log),
      fixDependencies2(arts, modules, crossVersion, checkMissing, fromSpace, log),
      fixScalaVersion2(dbuildDir, repoDir, arts),
      fixInterProjectResolver2bis(modules),
      fixCrossVersions2(crossVersion),
      fixIvyPaths2(log),
      fixScalaBinaryCheck2) flatMap { _(oldSettings, log) }
  }

  private def preparePublishSettings(in: BuildInput, log: ConsoleLogger, oldSettings: Seq[Setting[_]]) =
    Seq[Fixer](
      fixPublishTos2(in.outRepo.getAbsoluteFile),
      fixPGPs2,
      fixVersions2(in)) flatMap { _(oldSettings, log) }

  private def newState(state: State, extracted: Extracted, update: Seq[Setting[_]] => Seq[Setting[_]]) = {
    import extracted._

    val oldSettings = session.mergeSettings
    val newSettings = update(oldSettings)
    // Session strings can't be replayed, but are useful for debugging
    val newSessionSettings = newSettings map (a => (a, List("// dbuild-setup: " + a.key.toString)))
    // TODO - Should we honor build transformers? See transformSettings() in sbt's "Extracted.append()"
    val newSession = session.appendSettings(newSessionSettings)
    val newStructure = Load.reapply(oldSettings ++ newSettings, structure) // ( Project.showContextKey(newSession, structure) )
    // NB: setProject calls onLoad, which is why we restore it beforehand
    // (see calls in this file to restorePreviousOnLoad() )
    val newState = Project.setProject(newSession, newStructure, state)
    newState
  }

  // TODO: Note to self: is it wise to re-apply onLoad? Probably so, since it is reapplied at the end of the
  // now-modified state
  def restorePreviousOnLoad(previousOnLoad: State => State) =
    fixGeneric2(Keys.onLoad, "Resetting onLoad...") { _ => previousOnLoad }

  /** called by onLoad() during building */
  def rewire(state: State, previousOnLoad: State => State, fixPublishSettings: Boolean = false): State = {
    import distributed.support.sbt.SbtRunner.SbtFileNames._

    val extracted = Project.extract(state)
    val Some(baseDirectory) = sbt.Keys.baseDirectory in ThisBuild get extracted.structure.data
    val inputFile = rewireInputFile(baseDirectory)
    val rewireInfo = readValue[RewireInput](inputFile)
    val log = sbt.ConsoleLogger()
    if (rewireInfo.debug) log.setLevel(Level.Debug)

    val dbuildDir = baseDirectory / dbuildSbtDirName

    val modules = getModuleRevisionIds(state, rewireInfo.subproj, log)

    def restore(oldSettings: Seq[Setting[_]]) = restorePreviousOnLoad(previousOnLoad)(oldSettings, log)

    def publishSettings(oldSettings: Seq[Setting[_]]) = if (fixPublishSettings) {
      val Some(baseDirectory) = sbt.Keys.baseDirectory in ThisBuild get extracted.structure.data
      val inputFile = genArtsInputFile(baseDirectory)
      val generateArtifactsInfo = readValue[GenerateArtifactsInput](inputFile)
      preparePublishSettings(generateArtifactsInfo.info, log, oldSettings)
    } else Seq.empty

    def newSettings(oldSettings: Seq[Setting[_]]) =
      prepareCompileSettings(log, modules, dbuildDir, rewireInfo.in.localRepo, rewireInfo.in.artifacts,
        oldSettings, rewireInfo.crossVersion, rewireInfo.checkMissing, rewireInfo.in.fromSpace) ++
        publishSettings(oldSettings) ++ restore(oldSettings)

    // The property "dbuild.sbt-runner.last-msg" is normally be set by SbtRunner. However, rewire() may
    // also be called as part of the reload within "dbuild-setup", in which case the property will not
    // be set. We don't save the last error message into a file, in that case.
    val saveMsgDef: (State => State) => (State => State) = Option(System.getProperty("dbuild.sbt-runner.last-msg")) match {
      case Some(lastMsgFileName) => saveLastMsg(new File(lastMsgFileName), _)
      case None => identity
    }
    saveMsgDef(newState(_, extracted, newSettings))(state)
  }

  /**
   *  After all the calls to rewire() for all levels, generateArtifacts() is called at the main level only,
   *  via the dbuild-build command.
   */
  def generateArtifacts(state: State): State = {
    import distributed.support.sbt.SbtRunner.SbtFileNames._

    val extracted = Project.extract(state)
    val Some(baseDirectory) = sbt.Keys.baseDirectory in ThisBuild get extracted.structure.data
    val inputFile = genArtsInputFile(baseDirectory)
    val generateArtifactsInfo = readValue[GenerateArtifactsInput](inputFile)

    val log = sbt.ConsoleLogger()
    if (generateArtifactsInfo.debug) log.setLevel(Level.Debug)

    val Some(lastMsgFileName) = Option(System.getProperty("dbuild.sbt-runner.last-msg"))
    val lastMsgFile = new File(lastMsgFileName)

    val buildArts = buildArtsFile(baseDirectory)
    saveLastMsg(lastMsgFile, buildStuff(_, buildArts, generateArtifactsInfo))(state)
  }

  // this command can be called ONLY AFTER the rewiring is complete.
  private def buildIt = Command.command("dbuild-build")(generateArtifacts)

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(Keys.commands += buildIt)

  def extractArtifactLocations(org: String, version: String, artifacts: Map[Artifact, File],
    cross: CrossVersion, sv: String, sbv: String, sbtbv: String, isSbtPlugin: Boolean): Seq[model.ArtifactLocation] = {
    val crossSuffix = CrossVersion.applyCross("", CrossVersion(cross, sv, sbv))
    for {
      (artifact, file) <- artifacts.toSeq
    } yield model.ArtifactLocation(
      model.ProjectRef(artifact.name, org, artifact.extension, artifact.classifier),
      // we cannot use pluginAttrs(artifact) to produce the SbtPluginAttrs descriptor, as the extra attributes
      // are strangely not set in the Artifact while sbt is producing them.
      version, if (isSbtPlugin) "" else crossSuffix, if (isSbtPlugin) Some(SbtPluginAttrs(sbtbv, sbv)) else None)
  }

  def projectSettings: Seq[Setting[_]] = Seq(
    extractArtifacts <<= (Keys.organization, Keys.version, Keys.packagedArtifacts in Compile,
      Keys.crossVersion, Keys.scalaVersion, Keys.scalaBinaryVersion, Keys.sbtBinaryVersion, Keys.sbtPlugin) map extractArtifactLocations)
}
