package distributed.project.model
import graph._

case class BuildNode(value: ProjectConfigAndExtracted) extends Node[ProjectConfigAndExtracted] {
  def hasProject(dep: ProjectRef): Boolean = {   
    def hasArtifact(p: Project): Boolean =
      p.artifacts exists (dep == _)
    value.extracted.projects exists hasArtifact
  }
}

class BuildGraph(builds: Seq[ProjectConfigAndExtracted]) extends Graph[ProjectConfigAndExtracted, Nothing] {
  private val buildNodes = builds.map(b => new BuildNode(b))(collection.breakOut)
  override val nodes: Set[Nd] = buildNodes.toSet
  
  def nodeFor(build: ProjectConfigAndExtracted): Option[Nd] =
    (for {
      node <- nodes
      if node.value == build
    } yield node).headOption
  
  // Memoized ok?
  def edges(n: Nd): Seq[Ed] = edgeMap get n getOrElse Seq.empty
  private val edgeMap: Map[Nd, Seq[Ed]] = 
    buildNodes.map(n => n -> edgesImpl(n))(collection.breakOut)
  private def edgesImpl(n: Nd): Seq[Ed] = (for {
    p <- n.value.extracted.projects
    d <- p.dependencies
    m <- buildNodes
    if (m != n) && (m hasProject d)  
  } yield EmptyEdge(n, m)).toSet.toSeq
}
