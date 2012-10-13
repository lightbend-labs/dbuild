package com.typesafe.dsbt

import sbt._
import distributed.project.model
import distributed.support.sbt.SbtBuildConfig
import _root_.config.makeConfigString
import _root_.config.parseFileInto
import StateHelpers._
import DistributedBuildKeys._
import NameFixer.fixName


object DistributedRunner {
  
  // TODO - Config helper!
  def isValidProject(config: SbtBuildConfig, ref: ProjectRef): Boolean =
    config.config.projects.isEmpty || (config.config.projects exists ref.project.contains) 
  
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
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    
    def buildAggregate(f: (ProjectRef, State) => (State, ArtifactMap)): (State,ArtifactMap) =
      refs.foldLeft[(State, ArtifactMap)](state -> Seq.empty) { 
	    case ((state, amap), ref) => 
	      if(isValidProject(config, ref)) {
	    	val (state2,artifacts) = 
	    	  f(ref, state)
	    	state2 -> (amap ++ artifacts)
	      } else state -> amap    
      }
    if(config.config.measurePerformance) buildAggregate(timedBuildProject)
    else buildAggregate(untimedBuildProject)
  }
  
  def makeBuildResults(artifacts: ArtifactMap, localRepo: File): model.BuildArtifacts = 
    model.BuildArtifacts(artifacts, localRepo)
  
  def printResults(fileName: String, artifacts: ArtifactMap, localRepo: File): Unit = 
    IO.write(new java.io.File(fileName), makeConfigString(makeBuildResults(artifacts, localRepo)))
  
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
        m.copy(name = art.dep.name, revision=art.version, crossVersion = CrossVersion.Disabled)
      } getOrElse m
  }
    
  def fixLibraryDependencies(arts: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] = 
    s.asInstanceOf[Setting[Seq[ModuleID]]] mapInit { (_, old) =>        
      old map fixModule(arts)
    }
  
  def fixScalaVersion(arts: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] = {
    val scalaV = (for {
      artifact <- arts.view
      dep = artifact.dep
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
    } yield artifact.version).headOption
    val newSetting: Option[Setting[_]] = scalaV map { v =>
      println("Rewiring scala dependency to: " + v)
      s.asInstanceOf[Setting[String]].mapInit((_,_) => v)
    } 
    newSetting getOrElse s
  }
  
  def fixPublishTo(repo: Resolver)(s: Setting[_]): Setting[_] =
    s.asInstanceOf[Setting[Option[Resolver]]] mapInit { (_,_) => Some(repo) }

  def fixDependencies(locs: Seq[model.ArtifactLocation])(s: Setting[_]): Setting[_] = s.key.key match {
     case Keys.scalaVersion.key => fixScalaVersion(locs)(s)
     case Keys.libraryDependencies.key => fixLibraryDependencies(locs)(s)
     case Keys.crossVersion.key =>
       s.asInstanceOf[Setting[CrossVersion]] mapInit { (_,_) => CrossVersion.Disabled }
     case _ => s
  }
  
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
  
  def setupCmd(state: State): State =
    state
    //loadBuildArtifacts map (arts => fixSettings(config, state)) getOrElse state

  private def buildIt = Command.command("dsbt-build")(buildCmd)
  private def setItUp = Command.command("dsbt-setup")(setupCmd)

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt,
    Keys.commands += setItUp
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