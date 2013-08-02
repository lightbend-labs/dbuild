package graph

/** A directed graph of nodes. */
abstract class Graph[N,E] extends GraphCore[N,E] {

  // find the strongly connected components of size greater than one
  def cycles = tarjan.filter(_.size>1)
  
  def isCyclic: Boolean = cycles.nonEmpty
    
  /** Generates a digraph DOT file using a given DAG. */  
  def toDotFile(nodeNames: N => String): String = {
    // TODO - Better wrapping
    def makeName(s: N): String = '"' + nodeNames(s) + '"'
    val sb = new StringBuilder("digraph dependencies {")
    for {
      n <- nodes
      from = makeName(n.value)
      _ = sb append (" %s ;" format (from))
      e <- edges(n)
      to = makeName(e.to.value)
    } sb append (" %s -> %s ;" format (from, to))
    sb append "}"
    sb.toString
  }

  /** A Graph filtered to only contain a subset of the original nodes. */
  case class FilteredByNodesGraph(nodes: Set[Node[N]]) extends Graph[N,E] {
    assert((nodes -- Graph.this.nodes).isEmpty)
    def edges(n: Nd): Seq[Ed] =
      Graph.this.edges(n) filter { e => (nodes contains e.to) && (nodes contains e.from) }
  }
  
  def tarjanSubGraphs: Set[Graph[N,E]] =
    tarjan map { setEdges:Set[Node[N]] => FilteredByNodesGraph(setEdges) }

  // Note this is used to detect cycles. Breaks
  // the graph into strongly connected subgraphs.
  // We can use this to look for any subgraph containing more
  // than one node: we will have a cycle between those nodes.
  def tarjan: Set[Set[Node[N]]] = {
    val stack = new collection.mutable.Stack[Node[N]]
    val scc = new collection.mutable.ArrayBuffer[Set[Node[N]]]
    val index = new collection.mutable.ArrayBuffer[Node[N]]
    val lowLink = new collection.mutable.HashMap[Node[N], Int]
    val nodeList = nodes
    
    def tarjanImpl(v: Node[N]): Unit = {
      index += (v)
      lowLink(v) = index.size-1
      stack push v
      for {
        e <- edges(v)
        n = e.to
      } if(!(index contains n)) {
        tarjanImpl(n)
        lowLink(v) = math.min(lowLink(v), lowLink(n))
      } else if(stack contains n) {
        lowLink(v) = math.min(lowLink(v), index indexOf n)
      }
      if(lowLink(v) == (index indexOf v)) {
        val components = new collection.mutable.ArrayBuffer[Node[N]]
        def popLoop: Unit = stack.pop() match {          
          case `v` => components append v
          case n   => 
            components append n
            popLoop
        }
        popLoop
        scc append components.toSet
      }
    }
    for { 
      node <- nodeList
      if !(index contains node)
    } tarjanImpl(node)
    scc.toSet
  }

  def safeTopological: Seq[Node[N]] = {
    val c = cycles
    if (c.nonEmpty)
      // I have no access to logging here, so I have to
      // create a long error message instead
      sys.error((c map { comp: Set[Node[N]] =>
        comp mkString ("Found a cycle among the following:\n\n", "\n", "\n")
      }).mkString + "The graph is not acyclic.")
    else
      topological
  }
  
  /** Returns a topological ordering of a graph, or the
   * empty set if the graph is cyclic.
   */
  def topological: Seq[Node[N]] = 
    if(nodes.isEmpty) Seq.empty 
    else {
      val sequence = collection.mutable.ArrayBuffer.empty[Node[N]]
      // nodes with no outgoing edges.
      val bottomNodes = for {
        n <- nodes
        if edges(n).isEmpty
      } yield n
      // TODO - if bottom nodes is empty, don't error out?
      if(bottomNodes.isEmpty) sys.error("Cannot sort topologically if we have no bottom nodes!")
     val visited = collection.mutable.HashSet.empty[Node[N]]
      def visit(n: Node[N]): Unit = {
        if(!(visited contains n)) {
          visited += n
          for {
            m <- nodes if edges(m)  exists (_.to == n)
          } visit(m)
          sequence += n
        } else ()
      }
      bottomNodes foreach visit
      sequence.toSeq
    }
  
  def subGraphFrom(n: Node[N]): Set[Node[N]] = {
    assert(nodes(n), "Node is not contained inside the graph.")
    def findNodes(current: Seq[Node[N]], found: Set[Node[N]]): Set[Node[N]] = 
      if(current.isEmpty) found
      else {
        val head = current.head
        def spans = edges(head) map (_.to)
        if(found contains head) findNodes(current.tail, found)
        else findNodes(current.tail ++ spans, found + head)
      }
    findNodes(Seq(n), Set.empty)
  }

}