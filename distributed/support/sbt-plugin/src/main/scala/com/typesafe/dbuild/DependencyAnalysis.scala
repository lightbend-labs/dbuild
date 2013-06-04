package com.typesafe.dbuild

import sbt._
import distributed.project.model
import StateHelpers._
import NameFixer.fixName
import distributed.project.model.Utils.writeValue
import DistributedRunner.{isValidProject,verifySubProjects}

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
      } yield model.ProjectRef(fixName(a.name), d.organization, a.extension, a.classifier)
      
      // Project Artifacts
      val artifacts = for {
        a <- extracted get (Keys.artifacts in ref)
      } yield model.ProjectRef(fixName(a.name), organization, a.extension, a.classifier)
      
      // Append ourselves to the list of projects...
      dependencies :+ model.Project(
        name,
        organization,
        artifacts,
        deps)
    }
  /** Actually prints the dependencies to the given file. */
  def printDependencies(state: State, uri: String, file: String, projects:Seq[String]): Unit = {
    val extracted = Project.extract(state)
    import extracted._
    val allRefs=getProjectRefs(session.mergeSettings)
    verifySubProjects(projects, allRefs)
    val refs = if (projects.isEmpty)
      allRefs
    else {
      val requested = allRefs.filter(isValidProject(projects, _))
      // let's investigate whether any other local projects
      // are dependencies of the requested ones
      //
      // TODO: code is too convoluted; can it be simplified a little?
      val localModulesMap = allRefs map { r => (r.project -> extracted.get(Keys.projectID in r)) } toMap
      val localModulesIDs = localModulesMap.values.toList
      val localRefsMap = allRefs map { r => (r.project -> r) } toMap

      def dependencies(toScan: Seq[String], scanned: Map[String, ModuleID]): Map[String, ModuleID] = {
        toScan.toList match {
          case project :: rest =>
            val id = localModulesMap(project)
            val Some(Value(deps)) = Project.evaluateTask(Keys.projectDependencies in localRefsMap(project), state)
            def matc(id:ModuleID, d:ModuleID)=
              id.name == d.name && id.organization == d.organization && id.revision == d.revision
            val newDeps = deps filter {
              d => localModulesIDs.exists { id => matc(id, d) } && !scanned.exists { case (_, id) => matc(id, d) }
            }
            // Find the local projects for newDeps:
            val newDepsNames = newDeps map { d => val Some((n, _)) = localModulesMap.find { case (_, id) => matc(id,d) }; n }
            val newRest=(rest ++ newDepsNames).distinct
            val added=newRest diff rest
            if (added.size!=0)
              println("Adding these subprojects, as they are needed by "+project+" (and possibly by others): " + added.mkString(", "))
            dependencies(newRest, scanned + (project -> id))
          case _ => scanned
        }
      }
      val newProjectNames = dependencies(projects, Map()) map (_._1)
      if (newProjectNames.size != projects.size) {
        println("\n*** WARNING ***\n\nFurther subprojects will be included, as they are dependencies of the requested projects.")
        println((newProjectNames.toList diff projects).mkString("Added: ",", ",".\n"))
      }
      newProjectNames map localRefsMap
    }
    val deps = getProjectInfos(extracted, state, refs)    
    val meta = model.ExtractedBuildMeta(uri, deps)
    val output = new java.io.PrintStream(new java.io.FileOutputStream(file))
    try output println writeValue(meta)
    finally output.close()
  }
  
  /** The implementation of the print-deps command. */
  def printCmd(state: State): State = {
    val uri = System.getProperty("remote.project.uri")   
    val projects = (Option(System.getProperty("project.dependency.metadata.subprojects")) getOrElse "") match {
      case "" => Seq.empty
      case projs => projs.split(",").toSeq
    }
    (Option(System.getProperty("project.dependency.metadata.file"))
        foreach (f => printDependencies(state, uri, f, projects)))
    state
  }

  private def print = Command.command("print-deps")(printCmd)

  /** Settings you can add your build to print dependencies. */
  def printSettings: Seq[Setting[_]] = Seq(
    Keys.commands += print
  )
}