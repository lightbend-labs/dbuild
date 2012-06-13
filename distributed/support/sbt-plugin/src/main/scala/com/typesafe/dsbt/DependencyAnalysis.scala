package com.typesafe.dsbt

import sbt._

object DependencyAnalysis {
 /** Hashes a project dependency to just contain organization and name. */
  private def hashInfo(d: MyDependencyInfo) = d.organization + ":" + d.name
  /** Hashes a module ID to just contain organization and name. */
  private def hashModule(o: ModuleID) = o.organization + ":" + o.name
  
  // TODO - make a task that generates this metadata and just call it!
  
  /** Pulls the name/organization/version for each project in the CEL build */
  private def getProjectInfos(extracted: Extracted, state: State, refs: Iterable[ProjectRef]): Seq[MyDependencyInfo] =
    (Vector[MyDependencyInfo]() /: refs) { (dependencies, ref) =>
      val (_, pdeps) = extracted.runTask(Keys.projectDependencies in ref, state)
      dependencies :+ MyDependencyInfo(
        ref,
        extracted.get(Keys.name in ref),
        extracted.get(Keys.organization in ref),
        extracted.get(Keys.version in ref),
        extracted.get(Keys.projectID in ref),
        extracted.get(Keys.libraryDependencies in ref) ++ pdeps)
    }

  def printDependencies(state: State): State = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = (session.mergeSettings map (_.key.scope) collect {
      case Scope(Select(p @ ProjectRef(_,_)),_,_,_) => p
    } toSet)
    val deps = getProjectInfos(extracted, state, refs)
    for {
      file <- Option(System.getProperty("project.dependency.metadata.file"))
      output = new java.io.PrintStream(new java.io.FileOutputStream(file))
    } try output println PrettyPrint(SbtBuildMetaData(deps filterNot (_.organization == "removeme"), System.getProperty("remote.project.uri")))
      finally output.close()

    state
  }
  
  
  private def print = Command.command("print-deps")(printDependencies)

  /** Settings you can add your build to print dependencies. */
  def printSettings: Seq[Setting[_]] = Seq(
    Keys.commands += print
  )
}