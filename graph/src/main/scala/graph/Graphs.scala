package graph

/** A directed graph of nodes. */
abstract class Graph[N,E] extends GraphCore[N,E] {
  // find the strongly connected components of size greater than one
  lazy val cycles = tarjan.filter(_.size>1)
  lazy val isCyclic = cycles.nonEmpty
    
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
    // todo: memoize
    def edges(n: Nd): Seq[Ed] =
      Graph.this.edges(n) filter { e => (nodes contains e.to) && (nodes contains e.from) }
  }

  def tarjanSubGraphs: Set[Graph[N, E]] =
    tarjan map { setEdges: Set[Node[N]] => FilteredByNodesGraph(setEdges) }

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

  def checkCycles() = {
    if (isCyclic)
      // I have no access to logging here, so I have to
      // create a long error message instead.
      // Nodes of the cycle, in the right order
      val messages = (cycles map { comp: Set[Node[N]] =>
        def connections(from: Node[N]) = {
          (edges(from) filter { n => comp.contains(n.to) } groupBy { _.to } flatMap { _._2.toString }).mkString("", "\n", "\n")
        }
        "These projects are part of cycles, and are all reachable from each other:\n\n" + (comp map connections).mkString
      }).mkString
      throw new CycleException(messages + "The graph is not acyclic.")
    }
  }

  def safeTopological: Seq[Node[N]] = {
    checkCycles()
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

  /**
   * Traverses a graph beginning from the leaves, passing some state from
   * the leaves to their parent, and collecting the results in the order nodes were visited.
   * The function f() will receive the node and the results of all its direct and remote children.
   * The optional sort function will be used to sort children's results before passing them up.
   */
  def traverse[State](f: (Seq[State], N) => State)(sort: Option[(N, N) => Boolean] = None): Seq[State] = {
    // progressively reduces the graph (sub), accumulating results
    def subTraverse(sub: Graph[N, E], results: scala.collection.immutable.ListMap[Node[N], State]): Seq[State] = {
      def getLeaf = sub.nodes find (sub.edges(_).isEmpty)
      getLeaf match {
        case None => results.values.toSeq
        case Some(leaf) =>
          val children = (subGraphFrom(leaf) - leaf).toSeq
          // edges(leaf).map(e=>results(e.to)) for immediate children only
          val sorted = sort.map(s => children.sortWith((a, b) => s(a.value, b.value))) getOrElse children
          val out = f(sorted.map(results), leaf.value)
          val newGraph = sub.FilteredByNodesGraph(sub.nodes - leaf)
          val newResults = results + (leaf -> out)
          subTraverse(newGraph, newResults)
      }
    }
    checkCycles()
    subTraverse(this, scala.collection.immutable.ListMap[Node[N], State]())
  }
}

// In case of cycles, we use a special exception
// to return a more extended error description to the caller
case class CycleException(description: String) extends Exception("Cycle found")
