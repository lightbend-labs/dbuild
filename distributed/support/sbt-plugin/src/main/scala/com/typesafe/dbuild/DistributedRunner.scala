package com.typesafe.dbuild

import sbt._
import distributed.project.model
import distributed.support.sbt.SbtBuildConfig
import distributed.project.model.ArtifactLocation
import distributed.project.model.ArtifactSha
import distributed.project.model.Utils.{ writeValue, readValue }
import StateHelpers._
import DependencyAnalysis.{normalizedProjectNames,normalizedProjectName}
import DistributedBuildKeys._
import NameFixer.fixName
import java.io.File
import distributed.repo.core.LocalArtifactMissingException
import org.apache.ivy.core.module.id.ModuleRevisionId
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.BuildSubArtifactsOut


object DistributedRunner {

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

  def timedBuildProject(ref: ProjectRef, state: State): (State, ArtifactMap) = {
    println("Running timed build: "+ref.project)
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
    println("Running build: "+ref.project)
    val y = Stated(state).runTask(extractArtifacts in ref)
    (y.state, y.value)
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
        state2 -> merge(current, next)
    }
  }

  // verify that the requested projects in SbtBuildConfig actually exist
  def verifySubProjects(requestedProjects: Seq[String], refs: Seq[sbt.ProjectRef], baseDirectory: File): Unit = {
    if (requestedProjects.nonEmpty) {
      val uniq=requestedProjects.distinct
      if (uniq.size != requestedProjects.size) {
        sys.error("Some subprojects are listed twice: " + (requestedProjects.diff(uniq)).mkString("\"", "\", \"", "\"."))
      }
      val availableProjects = normalizedProjectNames(refs,baseDirectory)
      val notAvailable = requestedProjects.toSet -- availableProjects
      if (notAvailable.nonEmpty)
        sys.error("These subprojects were not found: " + notAvailable.mkString("\"", "\", \"", "\"."))
    } else  sys.error("Internal error: subproject list is empty")
  }

  def getSortedProjects(projects: Seq[String], refs: Seq[ProjectRef], baseDirectory: File): Seq[ProjectRef] = {
    verifySubProjects(projects, refs, baseDirectory)
    projects map { p => refs.find(ref => (p == normalizedProjectName(ref, baseDirectory))).get }
  }

  def makeBuildResults(artifacts: Seq[BuildSubArtifactsOut], localRepo: File): model.BuildArtifactsOut =
    model.BuildArtifactsOut(artifacts)

  def printResults(fileName: String, artifacts: Seq[BuildSubArtifactsOut], localRepo: File): Unit =
    IO.write(new File(fileName), writeValue(makeBuildResults(artifacts, localRepo)))

  def loadBuildConfig: Option[SbtBuildConfig] =
    for {
      f <- Option(System getProperty "project.build.deps.file") map (new File(_))
      deps = readValue[SbtBuildConfig](f)
    } yield deps

  // TODO - Here we rely on a sequence of artifact locations, and we try to do
  // the matching manually. Ideally, we should take our ModuleID, point Ivy to
  // the rematerialized repository, and ask Ivy whether the module can be
  // resolved against that repository. It requires a bit of code, but would
  // be somewhat more general, at least in principle.
  def fixModule(arts: Seq[model.ArtifactLocation])(m: ModuleID): ModuleID = {
      def expandName(a:Artifact) = {
        import a._
        classifier match {
          case None => fixName(name)
          case Some(clas) => fixName(name)+"-"+clas
        }
      }
      def findArt: Option[model.ArtifactLocation] =
      (for {
        artifact <- arts.view
        if artifact.info.organization == m.organization
        if artifact.info.name == fixName(m.name) || (m.explicitArtifacts map expandName).contains(artifact.info.name)
      } yield artifact).headOption
    findArt map { art =>
      m.copy(name = art.info.name+art.crossSuffix, revision = art.version, crossVersion = CrossVersion.Disabled)
    } getOrElse m
  }

  def inNScopes(n:Int) = if(n==1) "in one scope" else "in "+n+" scopes"
  
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
        Project.update(s.asInstanceOf[Setting[Option[sbt.Resolver]]].key) {
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
  def fixGenericK2[K](k: Scoped, f: K => K) = fixGenericTransform2(k) { s: Setting[K] => Project.update(s.key)(f) } _

  // Separate cases for settings and tasks (to keep the type inferencer happy)
  def fixGeneric2[K](k: SettingKey[K], m: String)(f: K => K) = fixGenericK2(k, f)(m)
  def fixGeneric2[K](k: TaskKey[K], m: String)(f: Task[K] => Task[K]) = fixGenericK2(k, f)(m)

  type Fixer = (Seq[Setting[_]], Logger) => Seq[Setting[_]]

  def fixCrossVersions2 =
    fixGeneric2(Keys.crossVersion, "Disabling cross versioning") { _ => CrossVersion.Disabled }

  // Altering allDependencies, rather than libraryDependencies, will also affect projectDependencies.
  // This is necessary in case some required inter-project dependencies have been explicitly excluded.
  def fixDependencies2(locs: Seq[model.ArtifactLocation]) =
    fixGeneric2(Keys.allDependencies, "Updating dependencies") { _ map { old => old map fixModule(locs) } }

  def fixVersions2(config: SbtBuildConfig) =
    fixGeneric2(Keys.version, "Updating version strings") { _ => config.info.version }

  def fixResolvers2(dbuildRepoDir: File) =
    fixGeneric2(Keys.resolvers, "Adding resolvers to retrieve build artifacts") { old =>
      // make sure to add our resolvers at the beginning!
      Seq(
        "dbuild-local-repo-maven" at ("file:" + dbuildRepoDir.getAbsolutePath()),
        Resolver.file("dbuild-local-repo-ivy", dbuildRepoDir)(Resolver.ivyStylePatterns)) ++
        (old filterNot { r =>
          val n = r.name; n == "dbuild-local-repo-maven" || n == "dbuild-local-repo-ivy"
        })
    }

  // we want to match against only one and precisely one scala version; therefore any
  // binary compatibility lookup machinery must be disabled
  def fixScalaBinaryVersions2 =
    fixGenericTransform2(Keys.scalaBinaryVersion) { s: Setting[String] =>
      val sc = s.key.scope
      Keys.scalaBinaryVersion in sc <<= Keys.scalaVersion in sc
    }("Setting Scala binary version") _

  // sbt will try to check the scala binary version we use in this project (the full version,
  // including suffixes) against what Ivy reports as the version of the scala library (which is
  // a shortened version). That generates tons of warnings; in order to disable that, we set
  // IvyScala.checkExplicit to false
  def fixScalaBinaryCheck2 =
    fixGeneric2(Keys.ivyScala, "Disabling Scala binary checking") { _ map { _.copy(checkExplicit=false) } }

  // We need to disable the inter-project resolver entirely. Otherwise, sbt will try to build all
  // of the dependent subprojects each time one of the subprojects is built, including some that
  // we may have explicitly excluded (as they are built in a different project, for instance)
  def fixInterProjectResolver2 =
    fixGeneric2(Keys.projectResolver, "Disabling inter-project resolver") { _ map { _ => new RawRepository(new ProjectResolver("inter-project", Map.empty)) } }

  // alternate version, which only removes the artifacts that are not part
  // of the selected subprojects. Might be more suitable for setupcmd; in this case,
  // local-publish-repo should not be added to the list of resolvers.
  def fixInterProjectResolver2bis(modules: Seq[ModuleRevisionId], log: Logger) =
    fixGenericTransform2(Keys.projectResolver) { r: Setting[Task[Resolver]] =>
      val sc = r.key.scope
      Keys.projectResolver in sc <<= (Keys.projectDescriptors in sc) map {
        m => 
          val k=m.filter {
            case (a,_) => modules exists { b => b.getOrganisation == a.getOrganisation && fixName(b.getName) == a.getName }
          }
          new RawRepository(new ProjectResolver("inter-project", k))
      }
    }("Patching the inter-project resolver") _

  // extract the ModuleRevisionIds of all the subprojects of this dbuild project (as calculated from exclusions, dependencies, etc).
  def getModuleRevisionIds(state: State, projects: Seq[String], log: Logger) =
    runAggregate[Seq[ModuleRevisionId], ModuleRevisionId](state, projects, Seq.empty) { _ :+ _ } {
      (proj, state, _) => (state, Project.extract(state).evalTask(Keys.ivyModule in proj, state).dependencyMapping(log)._1)
    }._2
    
  // In order to convince sbt to use the scala instance we need, we just generate a fictitious
  // "lib" directory, like the one that would be generated by ant dist, and set scalaHome to that
  // directory (see ./util/classpath/ScalaInstance.scala in sbt for details)
  // Java 6 has no symlinks facility, therefore the files need to be copied.
  //
  // repoDir is the local-repo, which should already contain the re-materialized files
  def fixScalaVersion2(dbuildDir: File, repoDir: File, locs: Seq[model.ArtifactLocation])(oldSettings: Seq[Setting[_]], log: Logger) = {
    customScalaVersion(locs).toSeq flatMap { ver =>
      val scalaHome = dbuildDir / "scala" / ver
      log.info("Preparing Scala binaries: version " + ver)
      generateScalaDir(scalaHome, repoDir, ver)
      fixGeneric2(Keys.scalaVersion, "Setting Scala version to: " + ver){ _ => ver }(oldSettings, log) ++
      fixGeneric2(Keys.scalaHome, "Setting Scala home"){ _ => Some(scalaHome) }(oldSettings, log)
    }
  }

  // get the custom scala version string, if one is present somewhere in the list of artifacts of this build  
  private def customScalaVersion(arts: Seq[distributed.project.model.ArtifactLocation]): Option[String] =
    (for {
      artifact <- arts.view
      dep = artifact.info
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
    } yield artifact.version).headOption

  def generateScalaDir(scalaHome: File, repoDir: File, ver: String) = {
    // sbt uses needs a small set of jars in scalaHome. I only copy those, therefore.
    val neededJars = Seq("scala-library", "scala-compiler")
    val optionalJars = Seq("scala-reflect", "jline", "fjbg")
    val org = "org.scala-lang"
    neededJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, true)
    optionalJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, false)
  }

  def retrieveJarFile(scalaHome: File, repoDir: File, org: String, version: String, needed: Boolean)(name: String) = {
    try IO.copyFile(jarFile(repoDir, org, name, version), scalaHome / "lib" / (name + ".jar"), false)
    catch {
      case e: Exception => if (needed)
        throw new LocalArtifactMissingException("Could not find needed jar in local repo: " + name + "-" + version + ".jar", e.getMessage)
    }
  }

  def jarFile(repoDir: File, org: String, name: String, version: String) =
    org.split('.').foldLeft(repoDir)(_ / _) / name / version / (name + "-" + version + ".jar")

  def fixPGPs2(oldSettings: Seq[Setting[_]], log: Logger) =
    fixGeneric2(Keys.skip, "Disabling PGP signing") { old => old map (_ => true) }(oldSettings.filter {
      _.key.scope.task.toOption match {
        case Some(scope) if scope.label.toString == "pgp-signer" => true
        case _ => false
      }
    }, log)

  def fixBuildSettings(config: SbtBuildConfig, state: State): State = {
    // TODO: replace with the correct logger
    val log = sbt.ConsoleLogger()
    log.info("Updating dependencies...")
    val extracted = Project.extract(state)
    import extracted._
    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dbuild")

    dbuildDirectory map { dbuildDir =>
      val repoDir = dbuildDir / "local-repo"

      val refs = getProjectRefs(extracted)

      // config.info.subproj is the list of projects calculated in DependencyAnalysis;
      // conversely, config.config.projects is the list specified in the
      // configuration file (in the "extra" section)
      val modules=getModuleRevisionIds(state, config.info.subproj, log)

      def newSettings(oldSettings:Seq[Setting[_]]) =
        preparePublishSettings(config, log, oldSettings) ++
          prepareCompileSettings(log, modules, dbuildDir, repoDir, config.info.artifacts.artifacts, oldSettings)

      newState(state, extracted, newSettings)

    } getOrElse {
      sys.error("Key baseDirectory is undefined in ThisBuild: aborting.")
    }
  }

  def printPR(state: State): Unit = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(extracted)
    refs foreach { ref =>
      println(" PROJECT: "+ref.project)
      println("inter-project resolver:")
      val resolvers = extracted.runTask(Keys.fullResolvers in ref, state)._2
      resolvers.filter(_.name=="inter-project") foreach { r=>
        println("\t%s: (%s) - %s" format (ref.project, r.name, r.toString))
      }
      println("projectResolver:")
      val resolver = extracted.runTask(Keys.projectResolver in ref, state)._2
      println("\tprojectResolver in %s: (%s)" format (ref.project, resolver))
      println("inter-project resolver (after the fact):")
      val resolvers2 = extracted.runTask(Keys.fullResolvers in ref, state)._2
      resolvers2.filter(_.name=="inter-project") foreach { r=>
        println("\t%s: (%s) - %s" format (ref.project, r.name, r.toString))
      }
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
  def buildStuff(state: State, resultFile: String, config: SbtBuildConfig): State = {
    val state2 = fixBuildSettings(config, state)
//    printResolvers(state2)
//    printPR(state2)

    println("Building project...")
    val refs = getProjectRefs(Project.extract(state2))
    val Some(baseDirectory) = Keys.baseDirectory in ThisBuild get Project.extract(state).structure.data
    val buildAggregate = runAggregate[(Seq[File],Seq[BuildSubArtifactsOut]),
      (Seq[File],BuildSubArtifactsOut)] (state2, config.info.subproj, (Seq.empty, Seq.empty)) {
        case ((oldFiles,oldArts),(newFiles,arts)) => (newFiles,oldArts :+ arts) } _

    // If we're measuring, run the build several times.
    val buildTask = if (config.config.measurePerformance) timedBuildProject _ else untimedBuildProject _

    def buildTestPublish(ref: ProjectRef, state6: State, previous:(Seq[File],Seq[BuildSubArtifactsOut])):
      (State, (Seq[File],BuildSubArtifactsOut)) = {
      
      val (state7, artifacts) = buildTask(ref, state6)
      val state8 = if (config.config.runTests) {
        println("Testing: " + ref.project)
        Project.extract(state7).runTask(Keys.test in (ref, Test), state7)._1
      } else state7
      println("Publishing: " + ref.project)
      val (state9, _) =
        Project.extract(state8).runTask(Keys.publish in ref, state8)

      // We extract the set of files published during this step by checking the
      // current set of files in the repository against the files we had previously
      val previousFiles=previous._1
      val localRepo=config.info.outRepo.getAbsoluteFile
      val currentFiles=(localRepo.***).get.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml")
      val newFilesShas=currentFiles.diff(previousFiles).map{LocalRepoHelper.makeArtifactSha(_,localRepo)}

      (state9, (currentFiles,BuildSubArtifactsOut(normalizedProjectName(ref,baseDirectory), artifacts, newFilesShas)))
    }

    val (state3,(files,artifactsAndFiles)) = buildAggregate(buildTestPublish)

    printResults(resultFile, artifactsAndFiles, config.info.outRepo)
    state3
  }

  /** The implementation of the dbuild-build command. */
  def buildCmd(state: State): State = {
    val resultFile = Option(System.getProperty("project.build.results.file"))
    val results = for {
      f <- resultFile
      config <- loadBuildConfig
    } yield buildStuff(state, f, config)
    results getOrElse state
  }

  def loadBuildArtifacts(readRepo: File, builduuid: String, thisProject: String, log: Logger) = {
    import distributed.repo.core._
    val cache = Repository.default
    val project = findRepeatableProjectBuild(builduuid, thisProject, log)
    log.info("Retrieving dependencies for " + project.uuid + " " + project.config.name)
    val uuids = project.transitiveDependencyUUIDs.toSeq
    (project,LocalRepoHelper.getArtifactsFromUUIDs(log.info, cache, readRepo, uuids))
  }

  def findRepeatableProjectBuild(builduuid: String, thisProject: String, log: Logger) = {
    import distributed.repo.core._
    log.info("Finding information for project " + thisProject + " in build " + builduuid)
    val cache = Repository.default
    val projects = (for {
      build <- LocalRepoHelper.readBuildMeta(builduuid, cache).toSeq
      allProjects = build.repeatableBuilds
      project <- allProjects.filter(_.config.name == thisProject)
    } yield project) // we know project names are unique
    if (projects.isEmpty) sys.error("There is no project named "+thisProject+" in build "+builduuid)
    if (projects.size >1) sys.error("Unexpected internal error; found multiple projects named "+thisProject+" in build "+builduuid)
    projects.head
  }

  private def prepareCompileSettings(log: ConsoleLogger, modules: Seq[ModuleRevisionId], dbuildDir: File,
      repoDir: File, arts: Seq[ArtifactLocation], oldSettings: Seq[Setting[_]]) = {
    Seq[Fixer](
          fixResolvers2(repoDir),
          fixDependencies2(arts),
          fixScalaVersion2(dbuildDir, repoDir, arts),
          fixInterProjectResolver2bis(modules, log),
          fixScalaBinaryVersions2,
//          fixCrossVersions2,
          fixScalaBinaryCheck2) flatMap { _(oldSettings, log) }
  }
  
  private def preparePublishSettings(config: SbtBuildConfig, log: ConsoleLogger, oldSettings: Seq[Setting[_]]) =
    Seq[Fixer](
        fixPublishTos2(config.info.outRepo.getAbsoluteFile),
        fixPGPs2,
        fixVersions2(config)
      ) flatMap { _(oldSettings, log) }


  private def newState(state: State, extracted: Extracted, update: Seq[Setting[_]] => Seq[Setting[_]]) = {
    import extracted._
    val oldSettings = session.mergeSettings
    val newSettings = update(oldSettings)
    // Session strings can't be replayed, but are useful for debugging
    val newSessionSettings = newSettings map (a => (a, List("// dbuild-setup: " + a.key.toString)))
    // TODO - Should we honor build transformers? See transformSettings() in sbt's "Extracted.append()"
    val newSession = session.appendSettings(newSessionSettings)
    val newStructure = Load.reapply(oldSettings ++ newSettings, structure) // ( Project.showContextKey(newSession, structure) )
    val newState = Project.setProject(newSession, newStructure, state)
    newState
  }


  def setupCmd(state: State, args: Seq[String]): State = {
    val log = sbt.ConsoleLogger()
    // TODO - here I just grab the console logger, but "last" won't work as dbuild-setup
    // is not a task. I could add a wrapper task around the command, though.

    // TODO - add help text
    if (args.length != 2) sys.error("Usage: dbuild-setup <builduuid> <projectNameInDBuild>")
    val builduuid = args(0)
    val project = args(1)

    // The dbuild-setup command accepts a builduuid, and optionally a string that should match the project string
    // of the current sbt project, as specified in the .dbuild project file (which may be arbitrary)
    // If specified, download the dependencies of the specified project; if not specified, download all of the
    // artifacts of all the projects listed under builduuid.
    val extracted = Project.extract(state)
    import extracted._
    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dbuild")

    // note: we don't include config.config.directory here; the user needs to be in the
    // right subdir before entering sbt, in any case, so we should be ok
    dbuildDirectory map { dbuildDir =>
      val repoDir = dbuildDir / "local-repo"
      val (proj,arts) = loadBuildArtifacts(repoDir, builduuid, project, log)
      if (arts.isEmpty) {
        log.warn("No artifacts are dependencies of project " + project + " in build " + builduuid)
        state
      } else {
        val modules=getModuleRevisionIds(state, proj.subproj, log)
        newState(state, extracted, prepareCompileSettings(log, modules, dbuildDir, repoDir, arts, _))
      }
    } getOrElse {
      log.error("Key baseDirectory is undefined in ThisBuild: aborting.")
      state
    }
  }

  private def buildIt = Command.command("dbuild-build")(buildCmd)
  private def setItUp = Command.args("dbuild-setup", "<builduuid> <projectNameInDBuild>")(setupCmd)
  // The "//" command does nothing, which is exactly what should happen if anyone tries to save and re-play the session
  private def comment = Command.args("//", "// [comments]") { (state, _) => state }

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt,
    Keys.commands += setItUp,
    Keys.commands += comment)

  def extractArtifactLocations(org: String, version: String, artifacts: Map[Artifact, File],
    cross: CrossVersion, sv: String, sbv: String): Seq[model.ArtifactLocation] = {
    val crossSuffix = CrossVersion.applyCross("", CrossVersion(cross, sv, sbv))
    for {
      (artifact, file) <- artifacts.toSeq
    } yield model.ArtifactLocation(
      model.ProjectRef(artifact.name, org, artifact.extension, artifact.classifier),
      version, crossSuffix)
  }
      
  // TODO - We need to publish too....
  def projectSettings: Seq[Setting[_]] = Seq(
    extractArtifacts <<= (Keys.organization, Keys.version, Keys.packagedArtifacts in Compile,
      Keys.crossVersion, Keys.scalaVersion, Keys.scalaBinaryVersion) map extractArtifactLocations)

}
