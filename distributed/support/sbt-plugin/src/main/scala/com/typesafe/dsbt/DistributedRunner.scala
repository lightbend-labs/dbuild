package com.typesafe.dsbt

import sbt._
import distributed.project.model
import distributed.project.BuildResultFileParser
import _root_.pretty.ConfigPrint
import StateHelpers._
import DistributedBuildKeys._
import NameFixer.fixName


object DistributedRunner {

  /** Actually prints the dependencies to the given file. */
  def addDistributedResolver(state: State, localRepo: File): State = {
    println("Adding distributed resolver...")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)

    // TODO - Alter publishTo to be local repo...
    
    val transformedSettings = 
      (session.mergeSettings)
    
    val newStructure2 = Load.reapply(transformedSettings, structure)
    Project.setProject(session, newStructure2, state)
  }
  // TODO - Use a specific key that allows posting other kinds of artifacts.
  // Maybe also use a platform-specific task such that we can expose
  // windows artifacts on windows, etc.
  def buildProject(state: State): (State, ArtifactMap) = {
    println("Building project")
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    refs.foldLeft[(State, ArtifactMap)](state -> Seq.empty) { 
      case ((state, amap), ref) => 
      val (state2,artifacts) = 
        extracted.runTask(extractArtifacts, state)
      state2 -> (amap ++ artifacts)
    }
  }
  
  def makeBuildResults(artifacts: ArtifactMap, localRepo: File): model.BuildArtifacts = 
    model.BuildArtifacts(artifacts, localRepo)
  
  def printResults(fileName: String, artifacts: ArtifactMap, localRepo: File): Unit = 
    IO.write(new java.io.File(fileName), ConfigPrint(makeBuildResults(artifacts, localRepo)))
  
  def loadBuildArtifacts: Option[model.BuildArtifacts] =
    for {
      f <- Option(System getProperty "project.build.deps.file") map (new File(_))
      deps <- BuildResultFileParser parseMetaFile f
    } yield deps
    
  def fixModule(arts: model.BuildArtifacts)(m: ModuleID): ModuleID = {
      def findArt: Option[model.ArtifactLocation] =
        (for {
          artifact <- arts.artifacts.view
          if artifact.dep.organization == m.organization
          if artifact.dep.name == fixName(m.name)
        } yield artifact).headOption
      findArt map (_.version) map { v => 
        println("Updating: " + m + " for version: " + v)
        m.copy(revision=v)
      } getOrElse m
  }
    
  def fixLibraryDependencies(arts: model.BuildArtifacts)(s: Setting[_]): Setting[_] = 
    s.asInstanceOf[Setting[Seq[ModuleID]]] mapInit { (_, old) =>        
      old map fixModule(arts)
    }
  
  def fixScalaVersion(arts: model.BuildArtifacts)(s: Setting[_]): Setting[_] = {
    val scalaV = (for {
      artifact <- arts.artifacts.view
      dep = artifact.dep
      if dep.organization == "org.scala-lang"
      if dep.name == "scala-library"
      _ = println("Detected scala vesion = " + artifact.version)
    } yield artifact.version).headOption
    val newSetting: Option[Setting[_]] = scalaV map { v =>
      println("Rewiring scala dependency to: " + v)
      s.asInstanceOf[Setting[String]].mapInit((_,_) => v)
    } 
    newSetting getOrElse s
  }

  def fixSetting(arts: model.BuildArtifacts)(s: Setting[_]): Setting[_] = s.key.key match {
    case Keys.scalaVersion.key        => 
      println("Found scala versoin setting!")
      fixScalaVersion(arts)(s) 
    case Keys.libraryDependencies.key => 
      fixLibraryDependencies(arts)(s)
    case _ => s
  } 
  
  def fixDependencies(arts: model.BuildArtifacts, state: State): State = {
    println("Updating dependencies.")
    val extracted = Project.extract(state)
    import extracted._
    val newSettings = session.mergeSettings map fixSetting(arts)
    import Load._      
    val newStructure2 = Load.reapply(newSettings, structure)
    Project.setProject(session, newStructure2, state)
  }
    
  def buildStuff(state: State, resultFile: String, arts: model.BuildArtifacts): State = {
    val state2 = addDistributedResolver(state, arts.localRepo)
    val state3 = fixDependencies(arts, state2)
    val (state4, artifacts) = buildProject(state3)
    // TODO - Publish artifacts...
    printResults(resultFile, artifacts, arts.localRepo)
    state4
  }
    
  /** The implementation of the print-deps command. */
  def buildCmd(state: State): State = {
    val resultFile = Option(System.getProperty("project.build.results.file"))
    val results = for {
      f <- resultFile
      arts <- loadBuildArtifacts
    } yield buildStuff(state, f, arts)
    results getOrElse state
  }

  private def buildIt = Command.command("dsbt-build")(buildCmd)

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt
  )
  
  def extractArtifactLocations(org: String, version: String, artifacts: Map[Artifact, File]): Seq[model.ArtifactLocation] =
    for {
      (artifact, file) <- artifacts.toSeq
     } yield model.ArtifactLocation(
         model.ProjectDep(artifact.name, org, artifact.extension, artifact.classifier), 
         file,
         version
       )
  
        
  // TODO - We need to publish too....
  def projectSettings: Seq[Setting[_]] = Seq(
      extractArtifacts <<= (Keys.organization, Keys.version, Keys.packagedArtifacts in Compile) map extractArtifactLocations
    )
}