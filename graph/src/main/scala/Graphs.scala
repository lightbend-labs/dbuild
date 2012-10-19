package graph


object Graphs {
  
  def isCyclic[N,E](graph: Graph[N,E]): Boolean = !isAcyclic(graph)
  def isAcyclic[N,E](graph: Graph[N,E]): Boolean = 
    tarjan(graph) forall (_ drop(1) isEmpty)
    
  /** Generates a digraph DOT file using a given DAG. */  
  def toDotFile[N](graph: Graph[N,_])(nodeNames: N => String): String = {
    // TODO - Better wrapping
    def makeName(s: N): String = '"' + nodeNames(s) + '"'
    val sb = new StringBuilder("digraph dependencies {")
    for {
      n <- graph.nodes
      e <- graph.edges(n)
      from = makeName(e.from.value)
      to = makeName(e.to.value)
    } sb append ("  %s -> %s;" format (from, to))
    sb append "}"
    sb.toString
  }
  
  
  def tarjanSubGraphs[N,E](graph: Graph[N,E]): Set[Graph[N,E]] =
    tarjan(graph) map { edges => new FilteredByNodesGraph(graph, edges) }
  
    // Note this is used to detect cycles.   Breaks
    // The graph into strongly arrowed graphs, or whatever the
    // technical term is.   We can use this to look for any
    // groups larger than 1 and we have a cycle between that set of nodes.
  def tarjan[N,E](graph: Graph[N,E]): Set[Set[Node[N]]] = {
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
  // TODO - use tarjan directly for nice error messages....
  def safeTopological[N,E](g: Graph[N,E]): Seq[Node[N]] =
    if(isCyclic(g)) sys.error("Graph is not acyclic!")
    else topological(g)
  
  /** Returns a topological ordering of a graph, or the
   * empty set if the graph is cyclical.
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

