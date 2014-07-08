package distributed.project.model
import graph._

case class BuildNode(value: ProjectConfigAndExtracted) extends Node[ProjectConfigAndExtracted] {
  /**
   * Find whether the dependency dep is provided by this node, and by which
   * project.
   */
  def findProject(dep: ProjectRef): Option[Project] = {
    def hasArtifact(p: Project): Boolean =
      p.artifacts exists (dep == _)
    value.extracted.projects find hasArtifact
  }
  override def toString() = "   Project \"" + value.config.name + "\". Contains the subprojects:\n" + (value.extracted.projInfo.zipWithIndex map {
    case (pi, index) =>
      "     Level " + index + ":\n" +
        (pi.projects map { p =>
          "      " + p.organization + ":" + p.name + "\n" +
            (if (p.dependencies.nonEmpty) "         depends on:\n" + p.dependencies.mkString("            ", "\n            ", "\n") else "")
        }).mkString
  }).mkString
}

case class BuildEdge(from: Node[ProjectConfigAndExtracted], to: BuildNode,
    value: (Project, ProjectRef)) extends Edge[ProjectConfigAndExtracted,(Project, ProjectRef)] {
  override def toString() = "   Project \"" + from.value.config.name +"\" uses "+value._2+", which is provided by: \""+to.value.config.name+"\"."
}

class BuildGraph(builds: Seq[ProjectConfigAndExtracted]) extends Graph[ProjectConfigAndExtracted, (Project, ProjectRef)] {
  private val buildNodes = builds.map(b => new BuildNode(b))(collection.breakOut)
  override val nodes: Set[Nd] = buildNodes.toSet

  private val nodeMap: Map[ProjectConfigAndExtracted, graph.Node[ProjectConfigAndExtracted]] =
    buildNodes.map(b => b.value -> b)(collection.breakOut)
  def nodeFor(build: ProjectConfigAndExtracted): Option[Nd] = nodeMap.get(build)
  def nodeForName(name: String): Option[Nd] = nodes find (_.value.config.name == name)

  // Memoized ok?
  def edges(n: Nd): Seq[Ed] = edgeMap get n getOrElse Seq.empty
  private val edgeMap: Map[Nd, Seq[Ed]] =
    buildNodes.map(n => n -> edgesImpl(n))(collection.breakOut)
  private def edgesImpl(n: Nd): Seq[Ed] = {
    def projMetaWithSpaces(e: ProjectConfigAndExtracted) = {
      // associate each "from" space with the dependencies of that level
      e.getSpace.fromStream zip (e.extracted.projInfo map { _.projects flatMap { _.dependencies } })
    }
    (for {
      m <- buildNodes if (m != n)
      (nSpace, deps) <- projMetaWithSpaces(n.value)
      d <- deps
      p <- m.findProject(d) if Utils.canSeeSpace(nSpace, m.value.getSpace.to)
      // The alternative below superficially makes sense, but is cause for even worse cycles in the Scala compiler compilation 
      //      if (
      //          (m != n) && (m.hasProject(d) && Utils.canSeeSpace(nSpace, mSpaces)) ||
      //              buildNodes.exists { x => x.hasProject(d) && 
      //            Utils.canSeeSpace(nSpace, x.value.getSpace.to) &&
      //            x.value.extracted.projects.flatMap(_.dependencies).exists { xd =>
      //             m.hasProject(xd) && Utils.canSeeSpace(nSpace, mSpaces) && !Utils.canSeeSpace(x.value.getSpace.from, mSpaces)
      //          }}
      //      )

      // store in the value the witness by which we reached m from n 
    } yield BuildEdge(n, m, (p, d))).toSet.toSeq // n depends on m (n.deps contains something provided by m)
  }
}
