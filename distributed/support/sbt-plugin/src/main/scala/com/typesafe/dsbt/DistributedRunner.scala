package com.typesafe.dsbt

import sbt._
import distributed.project.model
import _root_.pretty.ConfigPrint
import StateHelpers._
import DistributedBuildKeys._


object DistributedRunner {

  /** Actually prints the dependencies to the given file. */
  def addDistributedResolver(state: State, localRepo: File): State = {
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
  
    
  def buildStuff(state: State, resultFile: String, localRepo: File): State = {
    val state2 = addDistributedResolver(state, localRepo)
    // Fix Dependencies....
    val (state3, artifacts) = buildProject(state2)
    // TODO - Report artifacts....
    printResults(resultFile, artifacts, localRepo)
    state3
  }
    
  /** The implementation of the print-deps command. */
  def buildCmd(state: State): State = {
    // TODO - Read in configuration for this distributed build.
    val localRepo = Option(System.getProperty("project.build.publish.repo")) map (new File(_))
    val resultFile = Option(System.getProperty("project.build.results.file"))
    val results = for {
      f <- resultFile
      lr <- localRepo
    } yield buildStuff(state, f, lr)
    results getOrElse state
  }

  private def buildIt = Command.command("dsbt-build")(buildCmd)

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt
  )
  
  def extractArtifactLocations(org: String, artifacts: Map[Artifact, File]): Seq[model.ArtifactLocation] =
    for {
      (artifact, file) <- artifacts.toSeq
     } yield model.ArtifactLocation(
         model.ProjectDep(artifact.name, org, artifact.extension, artifact.classifier), 
         file
       )
  
        
  // TODO - We need to publish too....
  def projectSettings: Seq[Setting[_]] = Seq(
      extractArtifacts <<= (Keys.organization, Keys.packagedArtifacts in Compile) map extractArtifactLocations
    )
}