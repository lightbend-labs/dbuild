package graph


/** Abstract node in a graph.  A vertix.
 * May store a value for later calculations after graph algorithms are done.
 */
trait Node[N] {
  def value: N
}

/** An edge between two nodes of a graph.  Has a from and a to and
 * a potential value.   Useful for weighted algorithms.
 * 
 * Note: Always directed for this library.
 */
trait Edge[N,E] {
  def value: E
  def from: Node[N]
  def to: Node[N]
}

/** A directed graph of nodes. */
trait Graph[N,E] {
  type Nd = Node[N]
  type Ed = Edge[N,E]
  
  def nodes: Set[Nd]
  def edges(n: Nd): Seq[Ed]
}