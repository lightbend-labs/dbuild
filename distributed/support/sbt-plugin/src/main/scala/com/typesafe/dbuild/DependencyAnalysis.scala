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
  def printDependencies(state: State, uri: String, file: String, projects: Seq[String]): Unit = {
    val log = sbt.ConsoleLogger()

log.setLevel(Level.Debug)

    val extracted = Project.extract(state)
    import extracted._
    val allRefs = getProjectRefs(extracted)
    verifySubProjects(projects, allRefs)
    val refs = if (projects.isEmpty)
      allRefs
    else {
      val requested = allRefs.filter(isValidProject(projects, _))

      
      
      import graph._
      // first, let's linearize the list of subprojects. If this is not done,
      // when we try to build one of the (sbt) subprojects, multiple ones can be
      // built at once, which prevents us from finding easily which files are
      // created by which subprojects.

      // I introduce a local implementation of graphs. Please bear with me for a moment.
      // I use SimpleNode[ProjectRef] & SimpleEdge[ProjectRef,DepType]
      sealed class DepType
      case object Dependency extends DepType
      case object Aggregate extends DepType
      class SubProjGraph(projs: Seq[ProjectRef], directDeps: Map[ProjectRef, Set[ProjectRef]],
        directAggregates: Map[ProjectRef, Set[ProjectRef]]) extends Graph[ProjectRef, DepType] {
        private val nodeMap: Map[ProjectRef, graph.Node[ProjectRef]] = (projs map { p => (p, SimpleNode(p)) }).toMap
        private def wrapEdges(kind: DepType, edges: Map[ProjectRef, Set[ProjectRef]]) = edges map {
          case (from, to) => (nodeMap(from), to map nodeMap map { SimpleEdge(nodeMap(from), _, kind) } toSeq)
        }
        val nodes: Set[Node[ProjectRef]] = nodeMap.values.toSet
        private val edgeMap: Map[Node[ProjectRef], Seq[Edge[ProjectRef, DepType]]] = {
          val w1 = wrapEdges(Dependency, directDeps)
          val w2 = wrapEdges(Aggregate, directAggregates)
          (nodes map { n => n -> (w1(n) ++ w2(n)) }).toMap
        }
        def edges(n: Node[ProjectRef]): Seq[graph.Edge[ProjectRef, DepType]] = edgeMap(n)
      }

      // OK, now I need a topological ordering over all the defined projects, according to the inter-project
      // dependencies. I obtain the dependencies from "extracted", I build a graph, then I use the
      // topological sort facilities in the graph package. (NB: I could maybe recycle some equivalent sbt
      // facilities, that are certainly in there, somewhere)

      // NB2: I must consider in the ordering BOTH the regular dependencies, as well as the aggregations
      // That is necessary since running a task on an aggregate will cause in any case all of the subprojects
      // to run. The net result is actually messy in case I have projects:["a","b","group"] and "group"
      // aggregates a,b,c,d,e. It will then appear that the artifacts of "group" are those of c,d,e alone.
      // I wonder whether I should /DISABLE/ aggregates altogether. Or, I can use a better approach: include
      // the aggregate structure in the publication information saved at the end, then disable aggregation everywhere.
      // The result is then, for instance in the case above, I would "expand" group into a,b,c,d,e, and publish all of the
      // artifacts/files for each subproject separately. Of course, if the "deploy" includes "group", I deploy the
      // whole expanded group. This would actually be the most sensible solution, I believe. OK. It's quite a bit
      // of work, though.
      //
      // Summary: while building the graph, expand the aggregates. Add all of the aggregations as dependencies iun the graph.
      // While rewriting the settings, disable all of the aggregations. While deploying, expand (recursively) the aggregations, and deploy
      // the individual projects. While reloading, all of deployed artifacts for a dbuild project are reloaded in a single block, so we
      // no longer care about aggregates, at that time.
      val allProjRefsMap = (allRefs map { r => (r.project, r) }).toMap
      // only direct dependencies
      val allProjDeps = extracted.currentUnit.defined map { p =>
        (allProjRefsMap(p._1), p._2.dependencies map { _.project } toSet)
      }
      // only direct aggregate relationships (not transitive ones)
      val allProjs = extracted.structure.allProjects
      val allProjAggregates = allProjs map { p =>
        (allProjRefsMap(p.id), p.aggregate.toSet)
      } toMap

      log.debug("Dependencies among subprojects:")
      allProjDeps map { case (s, l) => log.debug(s.project + " -> " + l.map { _.project }.mkString(",")) }
      log.debug("Aggregates of subprojects:")
      allProjAggregates map { case (s, l) => log.debug(s.project + " -> " + l.map { _.project }.mkString(",")) }
      log.debug("Building graph...")
      val allProjGraph = new SubProjGraph(allRefs, allProjDeps, allProjAggregates)
      log.debug("The graph contains:")
      allProjGraph.nodes foreach { n =>
        log.debug("Node: " + n.value.project)
        allProjGraph.edges(n) foreach { e => log.debug("edge: " + n.value.project + " to: " + e.to.value.project + " (" + e.value + ")") }
      }

      // Let's sort!

      log.debug("sorting...")
      val allProjSorted = Graphs.safeTopological(allProjGraph)
      log.debug(allRefs.map { _.project }.mkString("original: ", ", ", "."))
      log.debug(allProjSorted.map { _.value.project }.mkString("sorted: ", ", ", "."))
      log.debug("dot: " + Graphs.toDotFile(allProjGraph)({ _.project }))

      // Awesome!

      // now it only becomes a bit tricky to find which subprojects should be included (check transitively the dependencies, and
      // whether they come from aggregates or dependencies). Also, DISABLE sbt aggregation (I do mine). And at the end, expand
      // aggregation in order to decide which files should be deployed if the name of an aggregate is requested in the list
      // of subprojects that should be deployed (and expand recursively!!!!!)

      
      // the code below is now obsolete, but we'll need to recycle some of it.
      
      // Now: let's investigate whether any other local projects
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
            def matc(id: ModuleID, d: ModuleID) =
              id.name == d.name && id.organization == d.organization && id.revision == d.revision
            val newDeps = deps filter {
              d => localModulesIDs.exists { id => matc(id, d) } && !scanned.exists { case (_, id) => matc(id, d) }
            }
            // Find the local projects for newDeps:
            val newDepsNames = newDeps map { d => val Some((n, _)) = localModulesMap.find { case (_, id) => matc(id, d) }; n }
            val newRest = (rest ++ newDepsNames).distinct
            val added = newRest diff rest
            if (added.size != 0)
              println("Adding these subprojects, as they are needed by " + project + " (and possibly by others): " + added.mkString(", "))
            dependencies(newRest, scanned + (project -> id))
          case _ => scanned
        }
      }
      val newProjectNames = projects // dependencies(projects, Map()) map (_._1)
      if (newProjectNames.size != projects.size) {
        println("\n*** WARNING ***\n\nFurther subprojects will be included, as they are dependencies of the requested projects.")
        println((newProjectNames.toList diff projects).mkString("Added: ", ", ", ".\n"))
      }
      newProjectNames map localRefsMap
    }
    val deps = getProjectInfos(extracted, state, refs)
    val Some(version) = Keys.version in currentRef get structure.data
    // return just this version string now; we will append to it more stuff prior to building
    val meta = model.ExtractedBuildMeta(uri, version, deps)
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