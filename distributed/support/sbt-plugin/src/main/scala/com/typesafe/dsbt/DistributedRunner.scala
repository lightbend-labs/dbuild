package com.typesafe.dsbt

import sbt._
import distributed.project.model
import _root_.pretty.ConfigPrint
import StateHelpers._
import DistributedBuildKeys._


object DistributedRunner {

  /** Actually prints the dependencies to the given file. */
  def addDistributedResolver(state: State): State = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    def addResolver(ref: ProjectRef) =
      Keys.resolvers in ref += DistributedDependencyResolver()
    
    val transformedSettings = 
      (session.mergeSettings ++
       (refs map addResolver))
    
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
  
  def makeBuildResults(artifacts: ArtifactMap): model.BuildArtifacts = 
    model.BuildArtifacts(artifacts)
  
  def printResults(fileName: String, artifacts: ArtifactMap): Unit = 
    IO.write(new java.io.File(fileName), ConfigPrint(makeBuildResults(artifacts)))
  
  /** The implementation of the print-deps command. */
  def buildCmd(state: State): State = {
    // TODO - Read in configuration for this distributed build.
    val state2 = addDistributedResolver(state)
    val (state3, artifacts) = buildProject(state2)
    // TODO - Report artifacts....
    val resultFile = Option(System.getProperty("project.build.results.file"))
    resultFile foreach (f => printResults(f, artifacts))
    state3
  }

  private def buildIt = Command.command("dsbt-build")(buildCmd)

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt
  )
  
  def extractArtifactLocations(org: String, artifacts: Map[Artifact, File]): Seq[model.ArtifactLocation] =
    for {
      (artifact, file) <- artifacts.toSeq
      // TODO - allow any old artifact....
      if artifact.classifier.isEmpty
      if artifact.extension == "jar"
     } yield model.ArtifactLocation(model.ProjectDep(artifact.name, org), file)
  
        
  
  def projectSettings: Seq[Setting[_]] = Seq(
      extractArtifacts <<= (Keys.organization, Keys.packagedArtifacts in Compile) map extractArtifactLocations
    )
}