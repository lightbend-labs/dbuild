package graph


object Graphs {
  
  // find the strongly connected components of size greater than one
  def cycles[N,E](graph: Graph[N,E]) = tarjan(graph).filter(_.size>1)
  
  def isCyclic[N,E](graph: Graph[N,E]): Boolean = cycles(graph).nonEmpty
    
  /** Generates a digraph DOT file using a given DAG. */  
  def toDotFile[N](graph: Graph[N,_])(nodeNames: N => String): String = {
    // TODO - Better wrapping
    def makeName(s: N): String = '"' + nodeNames(s) + '"'
    val sb = new StringBuilder("digraph dependencies {")
    for {
      n <- graph.nodes
      from = makeName(n.value)
      _ = sb append (" %s ;" format (from))
      e <- graph.edges(n)
      to = makeName(e.to.value)
    } sb append (" %s -> %s ;" format (from, to))
    sb append "}"
    sb.toString
  }
  
  
  def tarjanSubGraphs[N,E](graph: Graph[N,E]): Set[Graph[N,E]] =
    tarjan(graph) map { edges => new FilteredByNodesGraph(graph, edges) }

  // Note this is used to detect cycles. Breaks
  // the graph into strongly connected subgraphs.
  // We can use this to look for any subgraph containing more
  // than one node: we will have a cycle between those nodes.
  def tarjan[N, E](graph: Graph[N, E]): Set[Set[Node[N]]] = {
    val stack = new collection.mutable.Stack[Node[N]]
    val scc = new collection.mutable.ArrayBuffer[Set[Node[N]]]
    val index = new collection.mutable.ArrayBuffer[Node[N]]
    val lowLink = new collection.mutable.HashMap[Node[N], Int]
    val nodeList = graph.nodes
    
    def tarjanImpl(v: Node[N]): Unit = {
      index += (v)
      lowLink(v) = index.size-1
      stack push v
      for {
        e <- graph edges v
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

  def safeTopological[N, E](g: Graph[N, E]): Seq[Node[N]] = {
    val c = cycles(g)
    if (c.nonEmpty)
      // I have no access to logging here, so I have to
      // create a long error message instead
      sys.error((c map { comp: Set[Node[N]] =>
        comp mkString ("Found a cycle among the following:\n\n", "\n", "\n")
      }).mkString + "The graph is not acyclic.")
    else
      topological(g)
  }
  
  /** Returns a topological ordering of a graph, or the
   * empty set if the graph is cyclic.
   */
  def topological[N,E](g: Graph[N,E]): Seq[Node[N]] = 
    if(g.nodes.isEmpty) Seq.empty 
    else {
      val sequence = collection.mutable.ArrayBuffer.empty[Node[N]]
      // nodes with no outgoing edges.
      val bottomNodes = for {
        n <- g.nodes
        if g.edges(n).isEmpty
      } yield n
      // TODO - if bottom nodes is empty, don't error out?
      if(bottomNodes.isEmpty) sys.error("Cannot sort topologically if we have no bottom nodes!")
     val visited = collection.mutable.HashSet.empty[Node[N]]
      def visit(n: Node[N]): Unit = {
        if(!(visited contains n)) {
          visited += n
          for {
            m <- g.nodes
            if g edges m  exists (_.to == n)
          } visit(m)
          sequence += n
        } else ()
      }
      bottomNodes foreach visit
      sequence.toSeq
    }
  
  def subGraphFrom[N,E](g: Graph[N,E])(n: Node[N]): Set[Node[N]] = {
    assert(g.nodes(n), "Node is not contained inside the graph.")
    def findNodes(current: Seq[Node[N]], found: Set[Node[N]]): Set[Node[N]] = 
      if(current.isEmpty) found
      else {
        val head = current.head
        def spans = g edges n map (_.to)
        if(found contains head) findNodes(current.tail, found)
        else findNodes(current.tail ++ spans, found + head)
      }
    findNodes(Seq(n), Set.empty)
  }
}

