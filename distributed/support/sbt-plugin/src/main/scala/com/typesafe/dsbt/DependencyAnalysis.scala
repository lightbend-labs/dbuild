package com.typesafe.dsbt

import sbt._
import distributed.project.model
import _root_.config.makeConfigString
import StateHelpers._
import NameFixer.fixName

object DependencyAnalysis {
  // TODO - make a task that generates this metadata and just call it!
  
  /** Pulls the name/organization/version for each project in the build. */
  private def getProjectInfos(extracted: Extracted, state: State, refs: Iterable[ProjectRef]): Seq[model.Project] =
    (Vector[model.Project]() /: refs) { (dependencies, ref) =>
      val name = fixName(extracted.get(Keys.name in ref))
      val organization = extracted.get(Keys.organization in ref)
      
      // Project dependencies (TODO - Custom task for this...)
      val (_, pdeps) = extracted.runTask(Keys.projectDependencies in ref, state)
      val ldeps = extracted.get(Keys.libraryDependencies in ref)
      def artifactsNoEmpty(name: String, arts: Seq[Artifact]) =
        if(!arts.isEmpty) arts
        else Seq(Artifact(name))
      val deps = for {
        d <- (pdeps ++ ldeps)
        a <- artifactsNoEmpty(d.name, d.explicitArtifacts)
      } yield model.ProjectDep(fixName(a.name), d.organization, a.extension, a.classifier)
      
      // Project Artifacts
      val artifacts = for {
        a <- extracted get (Keys.artifacts in ref) 
      } yield model.ProjectDep(fixName(a.name), organization, a.extension, a.classifier)
      
      // Append ourselves to the list of projects...
      dependencies :+ model.Project(
        name,
        organization,
        artifacts,
        deps)
    }
  /** Actually prints the dependencies to the given file. */
  def printDependencies(state: State, uri: String, file: String): Unit = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = getProjectRefs(session.mergeSettings)
    val deps = getProjectInfos(extracted, state, refs)    
    val meta = model.ExtractedBuildMeta(uri, deps)
    val output = new java.io.PrintStream(new java.io.FileOutputStream(file))
    try output println makeConfigString(meta)
    finally output.close()
  }
  
  /** The implementation of the print-deps command. */
  def printCmd(state: State): State = {
    val uri = System.getProperty("remote.project.uri")
    (Option(System.getProperty("project.dependency.metadata.file")) 
        foreach (f => printDependencies(state, uri, f)))
    state
  }

  private def print = Command.command("print-deps")(printCmd)

  /** Settings you can add your build to print dependencies. */
  def printSettings: Seq[Setting[_]] = Seq(
    Keys.commands += print
  )
}