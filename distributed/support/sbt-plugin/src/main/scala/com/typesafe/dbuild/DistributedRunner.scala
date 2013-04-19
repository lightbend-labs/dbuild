package com.typesafe.dbuild

import sbt._
import distributed.project.model
import distributed.support.sbt.SbtBuildConfig
import distributed.project.model.Utils.{writeValue,readValue}
import StateHelpers._
import DistributedBuildKeys._
import NameFixer.fixName
import java.io.File
import distributed.repo.core.LocalArtifactMissingException


object DistributedRunner {
  
  // TODO - Config helper!
  def isValidProject(config: SbtBuildConfig, ref: ProjectRef): Boolean =
    config.config.projects.isEmpty || (config.config.projects exists (_ == ref.project))
  
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
     val y = Stated(state).runTask(extractArtifacts in ref)
    (y.state, y.value)
  } 
  
    
  // TODO - Use a specific key that allows posting other kinds of artifacts.
  // Maybe also use a platform-specific task such that we can expose
  // windows artifacts on windows, etc.
  def buildProject(state: State, config: SbtBuildConfig): (State, ArtifactMap) = {
    println("Building project")
    // Stage half the computation, including what we're churning through.
    val buildAggregate = runAggregate[ArtifactMap](state,config, Seq.empty)(_ ++ _) _
    // If we're measuring, run the build several times.
    if(config.config.measurePerformance) buildAggregate(timedBuildProject)
    else buildAggregate(untimedBuildProject)
  }
  
  /** Runs a serious of commands across projects, aggregating results. */
  private def runAggregate[T](state: State, config: SbtBuildConfig, init: T)(merge: (T,T) => T)(f: (ProjectRef, State) => (State, T)): (State, T) = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    verifySubProjects(config, refs)
    refs.foldLeft[(State, T)](state -> init) { 
	    case ((state, current), ref) => 
	      if(isValidProject(config, ref)) {
	    	val (state2, next) = 
	    	  f(ref, state)
	    	state2 -> merge(current, next)
	      } else state -> current    // TODO - if a project listed in the build does not exist, or list empty, it should really abort
      }
  }
  
  private def verifySubProjects(config: SbtBuildConfig, refs: Set[ProjectRef]): Unit = {
    // verify that the requested projects in SbtBuildConfig actually exist
    val requestedProjects=config.config.projects
    if (requestedProjects.nonEmpty) {
      val availableProjects=refs.map(_.project)
      val notAvailable=requestedProjects.toSet--availableProjects
      if (notAvailable.nonEmpty)
        sys.error("These subprojects were not found: "+notAvailable.mkString("\"","\",\"","\"."))
    }
  }
  
  def testProject(state: State, config: SbtBuildConfig): State = 
    if(config.config.runTests) {
      println("Testing project")
      val (state2, _) = runAggregate(state, config, List.empty)(_++_) { (proj, state) =>
        val y = Stated(state).runTask(Keys.test in Test)
        (y.state, List.empty)
      }
      state2
    } else state
  
  def makeBuildResults(artifacts: ArtifactMap, localRepo: File): model.BuildArtifacts = 
    model.BuildArtifacts(artifacts, localRepo)
  
  def printResults(fileName: String, artifacts: ArtifactMap, localRepo: File): Unit = 
    IO.write(new File(fileName), writeValue(makeBuildResults(artifacts, localRepo)))
  
  def loadBuildConfig: Option[SbtBuildConfig] =
    for {
      f <- Option(System getProperty "project.build.deps.file") map (new File(_))
      deps = readValue[SbtBuildConfig](f)
    } yield deps
    
    
  def fixModule(arts: Seq[model.ArtifactLocation])(m: ModuleID): ModuleID = {
      def findArt: Option[model.ArtifactLocation] =
        (for {
          artifact <- arts.view
          if artifact.info.organization == m.organization
          if artifact.info.name == fixName(m.name)
        } yield artifact).headOption
      findArt map { art => 
        // println("Updating: " + m + " to: " + art)
        // TODO - Update our publishing so we don't have a cross versions too.....
        // TODO - warning: cross-version settings should probably
        // /not/ be changed in case a new scala version is not specified
        // (in case scala is not part of the dbuild project file)
        m.copy(name = art.info.name, revision=art.version, crossVersion = CrossVersion.Disabled)
      } getOrElse m
  }
    
  // get the custom scala version string, if one is present somewhere in the list of artifacts of this build  
  private def customScalaVersion(arts: Seq[distributed.project.model.ArtifactLocation]): Option[String] =
    (for {
      artifact <- arts.view
      dep = artifact.info
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
    } yield artifact.version).headOption


  def fixScalaVersionSetting2(arts: Seq[model.ArtifactLocation], ver:String)(s: Setting[_]): Setting[_] =
    Project.update(s.asInstanceOf[Setting[String]].key) {_ => ver}
  
  // In order to convince sbt to use the scala instance we need, we just generate a fictitious
  // "lib" directory, like the one that would be generated by ant dist, and set scalaHome to that
  // directory (see ./util/classpath/ScalaInstance.scala in sbt for details)
  // Java 6 has no symlinks facility, therefore the files need to be copied.
  //
  // repoDir is the local-repo, which should already contain the re-materialized files
  def fixScalaHome2(scalaHome: File)(s: Setting[_]): Setting[_] =
    Project.update(s.asInstanceOf[Setting[Option[File]]].key) { _ => Some(scalaHome) }

  def jarFile(repoDir: File, org: String, name: String, version: String) =
    org.split('.').foldLeft(repoDir)(_ / _) / name / version / (name + "-" + version + ".jar")
  def retrieveJarFile(scalaHome: File, repoDir: File, org: String, version: String, needed: Boolean)(name: String) = {
    try IO.copyFile(jarFile(repoDir, org, name, version), scalaHome / "lib" / (name + ".jar"), false)
    catch {
      case e: Exception => if (needed)
        throw new LocalArtifactMissingException("Could not find needed jar in local repo: " + name + "-" + version + ".jar", e.getMessage)
    }
  }
  def generateScalaDir(scalaHome: File, repoDir: File, ver: String) = {
    // sbt uses needs a small set of jars in scalaHome. I only copy those, therefore.
    val neededJars = Seq("scala-library", "scala-compiler")
    val optionalJars = Seq("scala-reflect", "jline", "fjbg")
    val org = "org.scala-lang"

    neededJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, true)
    optionalJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, false)
  }

  def fixPublishTos2(repo: File, oldSettings: Seq[Setting[_]], log: Logger) = {
    val mavenRepo=Resolver.file("deploy-to-local-repo", repo)
    val lastSettings = oldSettings.filter(_.key.key == Keys.publishTo.key).groupBy(_.key.scope).map(_._2.last).toSeq
    if (lastSettings.nonEmpty) log.info("Updating publishTo repo in " + lastSettings.length + " scopes")
    lastSettings map { s =>
      val sc=s.key.scope
      // I need to check publishMavenStyle for this scope, and change repo accordingly
      Keys.publishTo in sc <<= (Keys.publishMavenStyle in sc) { mavenStyle:Boolean => Some(repo)}
//      Project.update(s.asInstanceOf[Setting[Option[Resolver]]].key) { x => Some(repo) }
    }
  }

  // the "...2" versions are somewhat similar, except they only generate a list of new settings rather than generating
  // a full list. So, fixDependencies() always creates a setting, while fixDependencies2() only generates new settings
  // TODO - once debugging is complete, code should consolidated, probably changing dbuild-build to use the new mechanism

  // fixDependencies2()
  // Generates a list of additional settings that can be tacked onto the current list of settings in order to
  // adapt dependencies in order to reflect the new values.
  // Note: the "libraryDependencies" setting is usually present in multiple places in the list of settings; each one may
  // modify the list (usually adding further dependencies). Hence, it would be unnecessary to create a new setting patching
  // each occurrence: duplicates and other weirdness may result.
  // We only inspect the most recent setting for each scope, adding a rewriting to that last one. In case "dbuild-setup" is
  // called multiple times, each will patch the previous dbuild one, which is however ok as we replace rather than adding;
  // ideally a "reload" should precede "dbuild-setup", however.
  //
  // so: start from the list of existing settings, and generate a list of settings that should be appended
  def fixDependencies2(locs: Seq[model.ArtifactLocation], oldSettings: Seq[Setting[_]], log: Logger) = {
    val lastSettings = oldSettings.filter(_.key.key == Keys.libraryDependencies.key).groupBy(_.key.scope).map(_._2.last).toSeq
    if (lastSettings.nonEmpty) log.info("Updating library dependencies in " + lastSettings.length + " scopes")
    lastSettings map { s =>
      // Equivalent to ~=
      Project.update(s.asInstanceOf[Setting[Seq[ModuleID]]].key) { old => old map fixModule(locs) }
    }
  }

  def fixCrossVersions2(oldSettings: Seq[Setting[_]], log: Logger) = {
    val lastSettings = oldSettings.filter(_.key.key == Keys.crossVersion.key).groupBy(_.key.scope).map(_._2.last).toSeq
    if (lastSettings.nonEmpty) log.info("Disabling cross versioning in " + lastSettings.length + " scopes")
    lastSettings map { s =>
      Project.update(s.asInstanceOf[Setting[CrossVersion]].key) { _ => CrossVersion.Disabled }
    }
  }
  
  // Fixing Scala version; similar to the above.
  //
  def fixScalaVersion2(dbuildDir: File, repoDir: File, locs: Seq[model.ArtifactLocation], oldSettings: Seq[Setting[_]], log: Logger) = {
    val verKeys = Seq(Keys.scalaVersion.key, Keys.scalaHome.key)
    val scalaV = customScalaVersion(locs)
    //change only if new version is requested, otherwise should not generate the new setting
    scalaV map { ver => oldSettings.filter { s => verKeys.contains(s.key.key) }.groupBy(_.key).map(_._2.last).toSeq.
      // subgroup again, by key name rather than key&scope
      groupBy(_.key.key).map { case (key,seq) =>
        key match {
          case Keys.scalaVersion.key => {
            log.info("Setting Scala version to: "+ver+" in "+seq.length+" scopes")
            seq map fixScalaVersionSetting2(locs, ver)
          }
          case Keys.scalaHome.key => {
        	val scalaHome = dbuildDir / "scala" / ver
            log.info("Preparing Scala binaries: version "+ver)
            generateScalaDir(scalaHome, repoDir, ver)
            log.info("Setting Scala home in "+seq.length+" scopes")
            seq map fixScalaHome2(scalaHome)
          }
          case _ => sys.error("Unexpected key in fixScalaVersion. This shouldn't happen.")
        }
      }.flatten
    } getOrElse Seq.empty
  }


  def fixPGPs2(oldSettings: Seq[Setting[_]], log: Logger) = {
    val lastSettings = oldSettings.filter(_.key.key == Keys.skip.key).groupBy(_.key.scope).map(_._2.last).filter {
      s => s.key.scope.task.toOption match {
        case Some(scope) if scope.label.toString == "pgp-signer" => true
        case _ => false
      }
    }.toSeq
    if (lastSettings.nonEmpty) log.info("Disabling PGP signing in " + lastSettings.length + " scopes")
    lastSettings map { s =>
      Project.update(s.asInstanceOf[Setting[Task[Boolean]]].key) { old => old map (_ => true)}
    }
  }

  def fixVersions2(config: SbtBuildConfig, oldSettings: Seq[Setting[_]], log: Logger):Seq[Setting[_]] = {
    val lastSettings = oldSettings.filter(_.key.key == Keys.version.key).groupBy(_.key.scope).map(_._2.last).toSeq
    if (lastSettings.nonEmpty) log.info("Updating version strings in " + lastSettings.length + " scopes")
    lastSettings map { s =>
      Project.update(s.asInstanceOf[Setting[String]].key) { value =>
        if(value endsWith "-SNAPSHOT") {
           value replace ("-SNAPSHOT", "-" + config.info.uuid)
        } else value
      }
    }
  }

  def fixSettings2(config: SbtBuildConfig, state: State): State = {
    // TODO: replace with the correct logger
    val log = sbt.ConsoleLogger()
    log.info("Updating dependencies...")
    val extracted = Project.extract(state)
    import extracted._
    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dbuild")

    dbuildDirectory map { dbuildDir =>
      val repoDir = dbuildDir / "local-repo"

      val refs = getProjectRefs(session.mergeSettings)
      val oldSettings = session.mergeSettings

      val newSettings = fixPublishTos2(config.info.outRepo.getAbsoluteFile, oldSettings, log) ++
        fixPGPs2(oldSettings, log) ++
        fixVersions2(config, oldSettings, log) ++
        fixResolvers2(repoDir, oldSettings, log) ++
        fixDependencies2(config.info.artifacts.artifacts, oldSettings, log) ++
        fixScalaVersion2(dbuildDir, repoDir, config.info.artifacts.artifacts, oldSettings, log) ++
        fixCrossVersions2(oldSettings, log)

      // TODO: remove duplication with setupCmd, below.
      // Also: consolidate the ...s2(...) methods into a common skeleton
      // Also: consolidate this routine with setupCmd, below.

      // Session strings can't be replayed; this info is only useful for debugging
      val newSessionSettings = newSettings map (a => (a, List("// " + a.key.toString)))
      // TODO - Should we honor build transformers? See transformSettings() in sbt's "Extracted.append()"
      val newSession = session.appendSettings(newSessionSettings)
      val newStructure = Load.reapply(oldSettings ++ newSettings, structure) // ( Project.showContextKey(newSession, structure) )
      val newState = Project.setProject(newSession, newStructure, state)
      newState
    } getOrElse {
      log.error("Key baseDirectory is undefined in ThisBuild: aborting.")
      state
    }
  }
  
  def publishProjects(state: State, config: SbtBuildConfig): State = {
    println("Publishing artifacts")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    refs.foldLeft[State](state) { case (state, ref) => 
      if(isValidProject(config, ref)) { 
        val (state2,_) = 
          extracted.runTask(Keys.publish in ref, state)
        state2
      } else state
    }
  }
  
  def printResolvers(state: State): Unit = {
    println("Using resolvers:")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    for {
      ref <- refs
      (_,resolvers) = extracted.runTask(Keys.fullResolvers in ref, state)
      r <- resolvers
    } println("\t(%s) - %s" format (r.name, r.toString))
  }
    
  def buildStuff(state: State, resultFile: String, config: SbtBuildConfig): State = {
    // printResolvers(state)
    val state3 = fixSettings2(config, state)
    val (state4, artifacts) = buildProject(state3, config)
    testProject(state4, config)
    // TODO - Use artifacts extracted to deploy!
    publishProjects(state4, config)
    printResults(resultFile, artifacts, config.info.outRepo)
    state4
  }
    
  /** The implementation of the print-deps command. */
  def buildCmd(state: State): State = {
    val resultFile = Option(System.getProperty("project.build.results.file"))
    val results = for {
      f <- resultFile
      config <- loadBuildConfig
    } yield buildStuff(state, f, config)
    results getOrElse state
  }

  def fixResolvers2(repo: File, oldSettings: Seq[Setting[_]], log: Logger) = {
    val lastSettings = oldSettings.filter(_.key.key == Keys.resolvers.key).last
    if (lastSettings.nonEmpty)
      log.info("Adding resolvers to retrieve build artifacts in " + lastSettings.length + " scopes")
    lastSettings map { s =>
      Project.update(s.asInstanceOf[Setting[Seq[Resolver]]].key) { old =>
        Seq("dbuild-local-repo-maven" at ("file:" + repo.getAbsolutePath()),
          Resolver.file("dbuild-local-repo-ivy", repo)(Patterns("[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"))) ++
          (old filterNot { r => val n = r.name; n == "dbuild-local-repo-maven" || n == "dbuild-local-repo-ivy" })
      }
    }
  }

  def loadBuildArtifacts(readRepo: File, builduuid: String, thisProject: Option[String], log: Logger) = {
    import distributed.repo.core._
    val cache = Repository.default
    val uuids = (for {
      build <- LocalRepoHelper.readBuildMeta(builduuid, cache).toSeq
      allProjects = build.repeatableBuilds
      uuid <- thisProject match {
        case Some(proj) => for {
          project <- allProjects.filter(_.config.name == proj)
          _ = log.info("Retrieving dependencies for " + project.uuid + " " + project.config.name)
          uuid <- project.transitiveDependencyUUIDs
        } yield uuid
        case None => {
          log.info("Retrieving all artifacts from "+allProjects.length+" projects")
          build.repeatableBuilds map { _.uuid }
        }
      }
    } yield uuid).distinct
    LocalRepoHelper.getArtifactsFromUUIDs(log.info, cache, readRepo, uuids)
  }

  def setupCmd(state: State, args: Seq[String]): State = {
    val log = sbt.ConsoleLogger()
    // TODO - here I just grab the console logger, but "last" won't work as dbuild-setup
    // is not a task. I could add a wrapper task around the command, though.

    // TODO - add help text
    if (args.length < 1 || args.length > 2) sys.error("Usage: dbuild-setup <builduuid> [<thisProjectInDsbt>]")
    val builduuid = args(0)

    // The dbuild-setup command accepts a builduuid, and optionally a string that should match the project string
    // of the current sbt project, as specified in the .dbuild project file (which may be arbitrary)
    // If specified, download the dependencies of the specified project; if not specified, download all of the
    // artifacts of all the projects listed under builduuid.
    val project = if (args.length == 1) None else Some(args(1))
    val extracted = Project.extract(state)
    import extracted._
    val dbuildDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dbuild")

    dbuildDirectory map { dbuildDir =>
      val repoDir = dbuildDir / "local-repo"
      val arts = loadBuildArtifacts(repoDir, builduuid, project, log)
      if (arts.isEmpty) {
        log.warn("No artifacts are dependencies" + { project map (" of project " + _) getOrElse ""} + " in build " + builduuid)
        state
      } else {
        log.info("Updating dependencies...")
        val oldSettings = session.mergeSettings
        val newSettings = fixResolvers2(repoDir, oldSettings, log) ++
          fixDependencies2(arts, oldSettings, log) ++
          fixScalaVersion2(dbuildDir, repoDir, arts, oldSettings, log) ++
          fixCrossVersions2(oldSettings, log)

        // Session strings can't be replayed, but are useful for debugging
        val newSessionSettings = newSettings map (a => (a, List("// dbuild-setup: " + a.key.toString)))
        // TODO - Should we honor build transformers? See transformSettings() in sbt's "Extracted.append()"
        val newSession = session.appendSettings(newSessionSettings)
        val newStructure = Load.reapply(oldSettings ++ newSettings, structure) // ( Project.showContextKey(newSession, structure) )
        val newState = Project.setProject(newSession, newStructure, state)
        newState
      }
    } getOrElse {
      log.error("Key baseDirectory is undefined in ThisBuild: aborting.")
      state
    }
  }

  private def buildIt = Command.command("dbuild-build")(buildCmd)
  private def setItUp = Command.args("dbuild-setup", "<builduuid> [<thisProjectInDsbt>]")(setupCmd)
  // The "//" command does nothing, which is exactly what should happen if anyone tries to save and re-play the session
  private def comment = Command.args("//", "// [comments]") {(state, _) => state}

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt,
    Keys.commands += setItUp,
    Keys.commands += comment
  )
  
  def extractArtifactLocations(org: String, version: String, artifacts: Map[Artifact, File]): Seq[model.ArtifactLocation] =
    for {
      (artifact, file) <- artifacts.toSeq
     } yield model.ArtifactLocation(
         model.ProjectRef(artifact.name, org, artifact.extension, artifact.classifier), 
         version
       )
  
        
  // TODO - We need to publish too....
  def projectSettings: Seq[Setting[_]] = Seq(
      extractArtifacts <<= (Keys.organization, Keys.version, Keys.packagedArtifacts in Compile) map extractArtifactLocations
    )
  }
