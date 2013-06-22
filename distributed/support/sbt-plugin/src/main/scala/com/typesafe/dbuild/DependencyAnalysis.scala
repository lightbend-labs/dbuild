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
  def printDependencies(state: State, uri: String, file: String, projects: Seq[String], excludedProjects: Seq[String]): Unit = {
    // TODO: fix logging
    val log = sbt.ConsoleLogger()

    val extracted = Project.extract(state)
    import extracted._

    val allRefs = getProjectRefs(extracted)

    // we rely on allRefs to not contain duplicates. Let's insert an additional sanity check, just in case
    val allRefsNames = allRefs.map{_.project}
    if (allRefsNames.distinct.size!=allRefsNames.size)
      sys.error(allRefsNames.mkString("Unexpected internal error: found duplicate name in ProjectRefs. List is: ",",",""))

    verifySubProjects(excludedProjects, allRefs)
    verifySubProjects(projects, allRefs)

    val excluded = if (excludedProjects.nonEmpty)
      allRefs.filter(isValidProject(excludedProjects, _))
    else
      Seq.empty

    val requested = {
      if (projects.isEmpty)
        allRefs.diff(excluded)
      else {
        val requestedPreExclusion = allRefs.filter(isValidProject(projects, _))
        if (requestedPreExclusion.intersect(excluded).nonEmpty) {
          log.warn(requestedPreExclusion.intersect(excluded).map{_.project}
            mkString("*** Warning *** You are simultaneously requesting and excluding some subprojects; they will be excluded. They are: ", ",", ""))
        }
        requestedPreExclusion.diff(excluded)
      }
    }

    // this will be the list of ProjectRefs that will actually be build, in the right sequence
    val refs = {
      
      import graph._
      // we need to linearize the list of subprojects. If this is not done,
      // when we try to build one of the (sbt) subprojects, multiple ones can be
      // built at once, which prevents us from finding easily which files are
      // created by which subprojects.
      // further, we also ned to find the full set of subprojects that are dependencies
      // of the ones that are listed in the configuration file. That is necessary both
      // in order to build them in the correct order, as well as in order to find in turn
      // their external dependencies, which we need to know about.

      // I introduce a local implementation of graphs. Please bear with me for a moment.
      // I use SimpleNode[ProjectRef] & SimpleEdge[ProjectRef,DepType]
      sealed class DepType
      case object Dependency extends DepType
      case object Aggregate extends DepType
      class SubProjGraph(projs: Seq[ProjectRef], directDeps: Map[ProjectRef, Set[ProjectRef]],
        directAggregates: Map[ProjectRef, Set[ProjectRef]]) extends Graph[ProjectRef, DepType] {
        val nodeMap: Map[ProjectRef, graph.Node[ProjectRef]] = (projs map { p => (p, SimpleNode(p)) }).toMap
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

      // I must consider in the ordering both the inter-project dependencies, as well as the aggregates.

      // utility map from the project name to its ProjectRef
      val allProjRefsMap = (allRefs map { r => (r.project, r) }).toMap

      // let's extract sbt inter-project dependencies (only direct ones)
      val allProjDeps = extracted.currentUnit.defined map { p =>
        (allProjRefsMap(p._1), p._2.dependencies map { _.project } toSet)
      }
      // similarly for the "aggregate" relationship (only direct ones, not the transitive set)
      // in order to extract it, I go through the list of all ResolvedProjects (in structure.allProjects)
      val allProjAggregates = extracted.structure.allProjects map { p =>
        (allProjRefsMap(p.id), p.aggregate.toSet)
      } toMap

      // some debugging won't hurt
      log.debug("Dependencies among subprojects:")
      allProjDeps map { case (s, l) => log.debug(s.project + " -> " + l.map { _.project }.mkString(",")) }
      log.debug("Aggregates of subprojects:")
      allProjAggregates map { case (s, l) => log.debug(s.project + " -> " + l.map { _.project }.mkString(",")) }
      log.debug("Building graph...")
      val allProjGraph = new SubProjGraph(allRefs, allProjDeps, allProjAggregates)
      log.debug("The graph contains:")
      allProjGraph.nodes foreach { n =>
        log.debug("Node: " + n.value.project)
        allProjGraph.edges(n) foreach { e => log.debug("edge: " + n.value.project + " to " + e.to.value.project + " (" + e.value + ")") }
      }

      // at this point I have my graph with all the relationships, and a list of "requested" projectRefs.
      // I need to find out 1) if there happen to be cycles (as a sanity check), and 2) the transitive set
      // of projects reachable from "requested", or rather the reachable subgraph.

      // 1) safeTopological() will check for cycles

      log.debug("sorting...")
      val allProjSorted = Graphs.safeTopological(allProjGraph)
      log.debug(allRefs.map { _.project }.mkString("original: ", ", ", ""))
      log.debug(allProjSorted.map { _.value.project }.mkString("sorted: ", ", ", ""))
      log.debug("dot: " + Graphs.toDotFile(allProjGraph)({ _.project }))

      // Excellent. 2) Now we need the set of projects transitively reachable from "requested".
      
      // note that excluded subprojects are only excluded individually: no transitive analysis
      // is performed on exclusions.
      // (if we are building all subprojects, and there are no exclusions, skip this step)

      if (projects.isEmpty && excluded.isEmpty) {
        val result = allProjSorted.map { _.value }.diff(excluded)
        log.info(result.map { _.project }.mkString("These subprojects will be built: ", ", ", ""))
        result
      } else {
        val needed = requested.foldLeft(Set[Node[ProjectRef]]()) { (set, node) =>
          set ++ Graphs.subGraphFrom(allProjGraph)(allProjGraph.nodeMap(node))
        } map { _.value }

        // In the end, our final sorted list (prior to explicit exclusions) is:
        // (keep the order of allProjSorted)
        val result = allProjSorted map { _.value } intersect needed.toSeq diff excluded

        // Have we introduced new subprojects? (likely). If so, warn the user.
        if (result.size != requested.size) {
          log.warn("*** Warning *** Some additional subprojects will be included, as they are needed by the requested subprojects.")
          log.warn(requested.map { _.project }.mkString("Originally requested: ", ", ", ""))
          log.warn((result diff requested).map { _.project }.mkString("Now added: ", ", ", ""))
        } else {
          log.info(result.map { _.project }.mkString("These subprojects will be built: ", ", ", ""))
        }
        
        // Have some of the needed subprojects been excluded? If so, print a warning.
        if (needed.intersect(excluded.toSet).nonEmpty) {
          log.warn("*** Warning *** Some subprojects are dependencies, but have been explicitly excluded.")
          log.warn("You may have to build them in a different project.")
          log.warn(needed.intersect(excluded.toSet).map { _.project }.mkString("Needed: ", ", ", ""))
        }

        result
      }
    }.reverse  // from the leaves to the roots

    val deps = getProjectInfos(extracted, state, refs)

    // TODO: why only the root version? We might as well grab that of each subproject
    val Some(version) = Keys.version in currentRef get structure.data
    // return just this version string now; we will append to it more stuff prior to building

    val meta = model.ExtractedBuildMeta(uri, version, deps, refs.map{_.project}) // return the new list of subprojects as well!
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
    val excluded = (Option(System.getProperty("project.dependency.metadata.excluded")) getOrElse "") match {
      case "" => Seq.empty
      case projs => projs.split(",").toSeq
    }
    (Option(System.getProperty("project.dependency.metadata.file"))
        foreach (f => printDependencies(state, uri, f, projects, excluded)))
    state
  }

  private def print = Command.command("print-deps")(printCmd)

  /** Settings you can add your build to print dependencies. */
  def printSettings: Seq[Setting[_]] = Seq(
    Keys.commands += print
  )
}