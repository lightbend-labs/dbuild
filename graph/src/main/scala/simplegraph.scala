package graph


/** A Graph filtered to only contain a subset of the original nodes. */
case class FilteredByNodesGraph[N,E](g: Graph[N,E], nodes: Set[Node[N]]) extends Graph[N,E] {
  assert((nodes -- g.nodes).isEmpty)
  def edges(n: Nd): Seq[Ed] = 
    g edges n filter { e => (nodes contains e.to) && (nodes contains e.from) }
}


case class SimpleNode[N,E](value: N) extends Node[N]
case class EmptyEdge[N](from: Node[N], to: Node[N]) extends Edge[N,Nothing] {
  def value: Nothing = sys.error("Empty edges have no data!")
}
case class SimpleEdge[N,E](from: Node[N], to: Node[N], value: E) extends Edge[N,E] {
  override def toString = from + " -> " + to + "(" + value + ")"
}
case class SimpleGraph[N,E](nodes: Set[Node[N]], _edges: Map[Node[N], Seq[Edge[N,E]]]) extends Graph[N,E]{
  def edges(n: Node[N]) = _edges get n getOrElse Seq.empty
}