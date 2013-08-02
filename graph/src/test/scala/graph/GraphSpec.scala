package graph

import org.specs2.mutable.Specification

object GraphData {
  val n1 = SimpleNode("node1")
  val n2 = SimpleNode("node2")
  val n3 = SimpleNode("node3")
  val n4 = SimpleNode("node4")
  val e1 = Seq(EmptyEdge(n1, n2), EmptyEdge(n1, n3))
  val e2 = Seq(EmptyEdge(n2, n3))
  val e3 = Seq(EmptyEdge(n3, n1))
  val cyclic = SimpleGraph[String, Nothing](
        nodes = Set(n1,n2,n3),
        _edges = Map(
          n1 -> e1,
          n2 -> e2,
          n3 -> e3
        ))
  val cyclic2 = SimpleGraph[String, Nothing](
        nodes = Set(n1,n2,n3,n4),
        _edges = Map(
          n1 -> e1,
          n2 -> e2,
          n3 -> e3
        ))
  val acyclic = SimpleGraph[String, Nothing](
        nodes = Set(n1,n2,n3),
        _edges = Map(
          n3 -> e3
        ))
  val emptyGraph = SimpleGraph[Nothing, Nothing](nodes = Set.empty,
      _edges = Map.empty)
}

object GraphSpec extends Specification {
  import GraphData._
  "Graphs.isCyclic" should {
    "detect full cycles" in {
      cyclic.isCyclic must beTrue
    }
    "detect partial cycles" in {
      cyclic2.isCyclic must beTrue
    }
    "Not detect cycles when there are none" in {
      acyclic.isCyclic must beFalse
    }
    "Handle empty" in {
      emptyGraph.isCyclic must beFalse
    }
  }
  
  "Graphs.topologicalSort" should {
    "sort topologically" in {
      val result = acyclic.topological
      (result indexOf n1) must beGreaterThan(result indexOf n3)
    }
    "fail on lack of bottom nodes" in {
      cyclic.topological must throwAn[Exception]
    }
    "doesn't fail on empty" in {
      (emptyGraph).topological must equalTo(Seq.empty)
    }
  }
  "Graphs.safeTopologicalSort" should {
    "sort topologically" in {
      val result = acyclic.safeTopological
      (result indexOf n1) must beGreaterThan(result indexOf n3)
    }
    "fail on full cycles" in {
      cyclic.topological must throwAn[Exception]
    }
    "fail on partial cycles" in {
      cyclic2.safeTopological must throwAn[Exception]
    }
  }
  "Graphs.subGraphFrom" should {
    "return the node passed" in {
      val result = acyclic.subGraphFrom(n1)
      result must equalTo(Set(n1))
    }
    "find subgraphs" in {
      val result = acyclic.subGraphFrom(n3)
      result must equalTo(Set(n1, n3))
    }
  }
}