package com.typesafe.dsbt

import sbt._
import distributed.project.model
import _root_.pretty.PrettyPrint
import StateHelpers._

object DistributedRunner {
  // TODO - make a task that generates this metadata and just call it!
  type ArtifactMap = Seq[(Artifact, File)]

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
      val (state2,fileMap) = 
        extracted.runTask(Keys.packagedArtifacts in Compile in ref, state)
      state2 -> (amap ++ fileMap)
    }
  }
  
  /** The implementation of the print-deps command. */
  def buildCmd(state: State): State = {
    // TODO - Read in configuration for this distributed build.
    val state2 = addDistributedResolver(state)
    val (state3, artifacts) = buildProject(state2)
    // TODO - Report artifacts....
    state3
  }

  private def buildIt = Command.command("dsbt-build")(buildCmd)

  /** Settings you can add your build to print dependencies. */
  def buildSettings: Seq[Setting[_]] = Seq(
    Keys.commands += buildIt
  )
}