package com.typesafe.dsbt

import sbt._
import distributed.project.model
import distributed.support.sbt.SbtBuildConfig
import _root_.config.makeConfigString
import _root_.config.parseFileInto
import StateHelpers._
import DistributedBuildKeys._
import NameFixer.fixName
import java.io.File


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
    refs.foldLeft[(State, T)](state -> init) { 
	    case ((state, current), ref) => 
	      if(isValidProject(config, ref)) {
	    	val (state2, next) = 
	    	  f(ref, state)
	    	state2 -> merge(current, next)
	      } else state -> current    
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
    IO.write(new File(fileName), makeConfigString(makeBuildResults(artifacts, localRepo)))
  
  def loadBuildConfig: Option[SbtBuildConfig] =
    for {
      f <- Option(System getProperty "project.build.deps.file") map (new File(_))
      deps <- parseFileInto[SbtBuildConfig](f)
    } yield deps
    
    
  def fixModule(arts: Seq[model.ArtifactLocation])(m: ModuleID): ModuleID = {
      def findArt: Option[model.ArtifactLocation] =
        (for {
          artifact <- arts.view
          if artifact.dep.organization == m.organization
          if artifact.dep.name == fixName(m.name)
        } yield artifact).headOption
      findArt map { art => 
        println("Updating: " + m + " to: " + art)
        // TODO - Update our publishing so we don't have a cross versions too.....
        // TODO - warning: cross-version settings should probably
        // /not/ be changed in case a new scala version is not specified
        // (in case scala is not part of the dbuild project file)
        m.copy(name = art.dep.name, revision=art.version, crossVersion = CrossVersion.Disabled)
      } getOrElse m
  }
    
  def fixLibraryDependencies(arts: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] = {
    s.asInstanceOf[Setting[Seq[ModuleID]]] mapInit { (_, old) => old map fixModule(arts) }
  }

  def fixLibraryDependencies2(arts: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] =
    // Should be equivalent to ~=
    Project.update(s.asInstanceOf[Setting[Seq[ModuleID]]].key) { old => old map fixModule(arts) }

  // get the custom scala version string, if one is present somewhere in the list of artifacts of this build  
  private def customScalaVersion(arts: Seq[distributed.project.model.ArtifactLocation]): Option[String] =
    (for {
      artifact <- arts.view
      dep = artifact.dep
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
    } yield artifact.version).headOption


  def fixScalaVersion(arts: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] = {
    val scalaV = customScalaVersion(arts)
    scalaV map { v => fixScalaVersionSetting(arts, v)(s) } getOrElse s
  }

  def fixScalaVersionSetting(arts: Seq[model.ArtifactLocation], ver:String)(s: Setting[_]): Setting[_] =
    s.asInstanceOf[Setting[String]].mapInit((_,_) => ver)

  def fixScalaVersionSetting2(arts: Seq[model.ArtifactLocation], ver:String)(s: Setting[_]): Setting[_] =
    Project.update(s.asInstanceOf[Setting[String]].key) {_ => ver}

  // In order to convince sbt to use the scala instance we need, we just generate a fictitious
  // "lib" directory, like the one that would be generated by ant dist, and set scalaHome to that
  // directory (see ./util/classpath/ScalaInstance.scala in sbt for details)
  // Java 6 has no symlinks facility, therefore the files need to be copied.
  //
  // repoDir is the local-repo, which should already contain the re-materialized files
  def fixScalaHome2(dsbtHome: File, repoDir: File, arts: Seq[model.ArtifactLocation], ver: String)(s: Setting[_]): Setting[_] = {
    val scalaHome = dsbtHome / "scala" / ver
    generateScalaDir(scalaHome, repoDir, arts, ver) // regenerate the "lib" dir immediately
    Project.update(s.asInstanceOf[Setting[Option[File]]].key) { _ => Some(scalaHome) }
  }

  def jarFile(repoDir: File, org: String, name: String, version: String) =
    org.split('.').foldLeft(repoDir)(_ / _) / name / version / (name + "-" + version + ".jar")
  def retrieveJarFile(scalaHome: File, repoDir: File, org: String, version: String, needed: Boolean)(name: String) = {
    try IO.copyFile(jarFile(repoDir, org, name, version), scalaHome / "lib" / (name + ".jar"), false)
    catch {
      // TODO - use a RepositoryException
      case t: Throwable => if (needed) sys.error("Could not find needed jar in local repo: " + name + "-" + version + ".jar")
    }
  }
  def generateScalaDir(scalaHome: File, repoDir: File, arts: Seq[model.ArtifactLocation], ver: String) = {
    // sbt uses needs a small set of jars in scalaHome. I only copy those, therefore.
    val neededJars = Seq("scala-library", "scala-compiler")
    val optionalJars = Seq("scala-reflect", "jline", "fjbg")
    val org = "org.scala-lang"

    neededJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, true)
    optionalJars foreach retrieveJarFile(scalaHome, repoDir, org, ver, false)
  }

  def fixPublishTo(repo: Resolver)(s: Setting[_]): Setting[_] =
    s.asInstanceOf[Setting[Option[Resolver]]] mapInit { (_, _) => Some(repo) }

  def fixDependencies(locs: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] = s.key.key match {
    case Keys.scalaVersion.key => fixScalaVersion(locs)(s)
    case Keys.libraryDependencies.key => fixLibraryDependencies(locs)(s)
    case Keys.crossVersion.key =>
      s.asInstanceOf[Setting[CrossVersion]] mapInit { (_, _) => CrossVersion.Disabled }
    case _ => s
  }

  // the "...2" versions are somewhat similar, except they only generate a list of new settings rather than generating
  // a full list. So, fixDependencies() always creates a setting, while fixDependencies2() only generates new settings
  // TODO - once debugging is complete, code should consolidated, probably changing dsbt-build to use the new mechanism

  // fixDependencies2()
  // Generates a list of additional settings that can be tacked onto the current list of settings in order to
  // adapt dependencies in order to reflect the new values.
  // Note: the "libraryDependencies" setting is usually present in multiple places in the list of settings; each one may
  // modify the list (usually adding further dependencies). Hence, it would be unnecessary to create a new setting patching
  // each occurrence: duplicates and other weirdness may result.
  // We only inspect the most recent setting for each scope, adding a rewriting to that last one. In case "dsbt-setup" is
  // called multiple times, each will patch the previous dsbt one, which is however ok as we replace rather than adding;
  // ideally a "reload" should precede "dsbt-setup", however.
  //
  // so: start from the list of existing settings, and generate a list of settings that should be appended
  def fixDependencies2(locs: Seq[model.ArtifactLocation], oldSettings: Seq[Setting[_]]) =
    oldSettings.
      filter(_.key.key == Keys.libraryDependencies.key).
      groupBy(_.key.scope).
      map(_._2.last).toSeq.
      map(fixLibraryDependencies2(locs))

  // Fixing Scala version; similar to the above.
  //
  def fixScalaVersion2(dsbtDir: File, repoDir: File, locs: Seq[model.ArtifactLocation], oldSettings: Seq[Setting[_]]) = {
    val verKeys = Seq(Keys.scalaVersion.key, Keys.crossVersion.key, Keys.scalaHome.key)
    val scalaV = customScalaVersion(locs)
    //change only if new version is requested, otherwise should not generate the new setting
    scalaV map { ver => oldSettings.filter { s => verKeys.contains(s.key.key) }.groupBy(_.key).map(_._2.last).toSeq.
      map { s =>
        s.key.key match {
          case Keys.scalaVersion.key => fixScalaVersionSetting2(locs, ver)(s)
          case Keys.crossVersion.key => fixCrossVersion2(s)
          case Keys.scalaHome.key => fixScalaHome2(dsbtDir, repoDir, locs, ver)(s)
          // TODO - change into an Exception
          case _ => sys.error("Unexpected key in fixScalaVersion. This shouldn't happen.")
        }
      }
    } getOrElse Seq.empty
  }

  private def fixCrossVersion2(s: sbt.Project.Setting[_]): sbt.Project.Setting[sbt.CrossVersion] =
    Project.update(s.asInstanceOf[Setting[CrossVersion]].key) { _ => CrossVersion.Disabled }

      
  def fixSetting(config: SbtBuildConfig)(s: Setting[_]): Setting[_] = s.key.key match {
    case Keys.publishTo.key =>
      fixPublishTo(Resolver.file("deploy-to-local-repo", config.info.outRepo.getAbsoluteFile))(s)
    // Here's our hack to *disable* PGP signing in builds we pull in.
    // TOO many global projects enable the PGP plugin by default...
    case Keys.skip.key =>
      s.key.scope.task.toOption match {
        case Some(scope) if scope.label.toString == "pgp-signer" => 
          s.asInstanceOf[Setting[Task[Boolean]]].mapInit((_,old) => old map (_ => true))
        case _ => s
      }
    case Keys.version.key => 
      // TODO - Modify version to be unique, not snapshot.
      s.asInstanceOf[Setting[String]] mapInit { (key, value) =>
        if(value endsWith "-SNAPSHOT") {
           
           value replace ("-SNAPSHOT", "-" + config.info.uuid)
        } else value
      } 
    case _ => fixDependencies(config.info.arts.artifacts)(s)
  } 
  
  def fixSettings(config: SbtBuildConfig, state: State): State = {
    println("Updating dependencies.")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    //val localRepoResolver: Option[Resolver] = Some("deploy-to-local-repo" at arts.localRepo.getAbsoluteFile.toURI.toASCIIString) 
    val newSettings = session.mergeSettings map fixSetting(config)
    import Load._      
    val newStructure2 = Load.reapply(newSettings, structure)
    Project.setProject(session, newStructure2, state)
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
    printResolvers(state)
    val state3 = fixSettings(config, state)
    val (state4, artifacts) = buildProject(state3, config)
    // TODO - TEST Projects, only if flag enabled.
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

  def fixResolvers2(repo: File, oldSettings: Seq[Setting[_]]) =
    oldSettings.filter(_.key.key == Keys.resolvers.key).last.map { s =>
      println("Fixing resolvers...")
      Project.update(s.asInstanceOf[Setting[Seq[Resolver]]].key) { old =>
        Seq("dsbt-local-repo-maven" at ("file:" + repo.getAbsolutePath()),
          Resolver.file("dsbt-local-repo-ivy", repo)(Patterns("[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"))) ++
          (old filterNot { r => val n = r.name; n == "dsbt-local-repo-maven" || n == "dsbt-local-repo-ivy" })
      }
    }

  def loadBuildArtifacts(repoDirectory: Option[File], builduuid: String, thisProject: Option[String] /*,log:Logger*/ ) = {
    import distributed.repo.core._
    val cache = Repository.default
    repoDirectory map { readRepo =>
      val uuids = (for {
        build <- LocalRepoHelper.readBuildMeta(builduuid, cache).toSeq
        project <- build.repeatableBuilds if (thisProject match {
          case Some(proj) => project.config.name == proj
          case None => true
        })
        uuid <- project.transitiveDependencyUUIDs
        // _ = log.info("Materializing dependencies for " + project.uuid + " " + project.config.name)
        _ = println("Retrieving dependencies for " + project.uuid + " " + project.config.name)
      } yield uuid).distinct
      for {
        uuid <- uuids
        art <- LocalRepoHelper.materializeProjectRepository(uuid, cache, readRepo)
        // _ = log.info("Materializing: " + uuid + ": " + art)
        _ = println("Retrieving: " + uuid + ": " + art)
      } yield art
    }
  }

  def setupCmd(state: State, args: Seq[String]): State = {
    // TODO - streams here is empty. Why?
    //    val streamsKey = Keys.streams
    //    val log = Project.evaluateTask(streamsKey, state) match {
    //      case Some(Value(s)) => s
    //      case _ => sys.error("Cannot obtain the streams key. This shouldn't happen.")
    //    }

    // TODO - add help text
    if (args.length < 1 || args.length > 2) sys.error("Usage: dsbt-setup <builduuid> [<thisProjectInDsbt>]")
    val builduuid = args(0)

    // The command accepts a builduuid, and optionally a string that should match the project string
    // of the current sbt project, as specified in the .dsbt project file (which apparently may be arbitrary)
    // If specified, download the dependencies of the specified project; if not specified, download the dependencies
    // of all the projects listed under builduuid. Please note that the project files themselves are not
    // downloaded in this case, if no other project in the list depends on them.
    // Example: if the build lists scala, scalacheck, akka-actors, where scalacheck needs scala, and akka-actors
    // needs scala and scalacheck, only scala and scalacheck will be loaded, which completes the set of dependencies
    // needed for sbt debugging (under dsbt-setup) of any of the three.
    val project = if (args.length == 1) None else Some(args(1))
    val extracted = Project.extract(state)
    import extracted._
    val dsbtDirectory = Keys.baseDirectory in ThisBuild get structure.data map (_ / ".dsbt")
    def makeRepoDir(dsbtDirectory: File) = dsbtDirectory / "local-repo"
    val repoDirectory = dsbtDirectory map makeRepoDir

//     def debugSettings(state: State, settings: Seq[Setting[_]]) = {
//       settings foreach { setting =>
//         println(BuiltinCommands.inspectOutput(state, InspectOption.Details(true), setting.key))
//       }
//     }

    loadBuildArtifacts(repoDirectory, builduuid, project) map { arts =>
      println("Updating dependencies.")
      // TODO - clean up this ugly chain of options/maps, and add diagnostics for the user,
      // in case anything should go wrong
      dsbtDirectory map { dsbtDir =>
        repoDirectory map { repoDir =>
          val oldSettings = session.mergeSettings
          val newSettings = fixResolvers2(repoDir, oldSettings) ++
            fixDependencies2(arts, oldSettings) ++
            fixScalaVersion2(dsbtDir, repoDir, arts, oldSettings)

          // TODO - build transformers should be included, mirroring sbt's "Extracted.append()" (also in dsbt-build)
          // val appendSettings = Load.transformSettings(Load.projectScope(currentRef), currentRef.build, rootProject, newSettings)

          // these session strings are of no use for replaying, but they are great for debugging
          val newSessionSettings = newSettings map (a => (a, List("// dsbt-setup: " + a.key.toString)))
          val newSession = session.appendSettings(newSessionSettings)
          val newStructure = Load.reapply(oldSettings ++ newSettings, structure) // ( Project.showContextKey(newSession, structure) )
          val newState = Project.setProject(newSession, newStructure, state)

//          println("In the new state:")
//          debugSettings(newState,newSettings)
          
          newState
        } getOrElse state
      } getOrElse state
    } getOrElse state
  }

  private def buildIt = Command.command("dsbt-build")(buildCmd)
  private def setItUp = Command.args("dsbt-setup", "<builduuid> [<thisProjectInDsbt>]")(setupCmd)
  // TODO - debugging only
  private def comment = Command.args("//", "// [comments]") {(state: State, args: Seq[String])=> state}

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
