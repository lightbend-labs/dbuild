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
      Graphs.isCyclic(cyclic) must beTrue
    }
    "detect partial cycles" in {
      Graphs.isCyclic(cyclic2) must beTrue
    }
    "Not detect cycles when there are none" in {
      Graphs.isCyclic(acyclic) must beFalse
    }
    "Handle empty" in {
      Graphs.isCyclic(emptyGraph) must beFalse
    }
  }
  
  "Graphs.topologicalSort" should {
    "sort topologically" in {
      val result = Graphs.topological(acyclic)
      (result indexOf n1) must beGreaterThan(result indexOf n3)
    }
    "fail on lack of bottom nodes" in {
      Graphs.topological(cyclic) must throwAn[Exception]
    }
    "doesn't fail on empty" in {
      Graphs.topological[Nothing, Nothing](emptyGraph) must equalTo(Seq.empty)
    }
  }
  "Graphs.safeTopologicalSort" should {
    "sort topologically" in {
      val result = Graphs.safeTopological(acyclic)
      (result indexOf n1) must beGreaterThan(result indexOf n3)
    }
    "fail on full cycles" in {
      Graphs.topological(cyclic) must throwAn[Exception]
    }
    "fail on partial cycles" in {
      Graphs.safeTopological(cyclic2) must throwAn[Exception]
    }
  }
  "Graphs.subGraphFrom" should {
    "return the node passed" in {
      val result = Graphs.subGraphFrom(acyclic)(n1)
      result must equalTo(Set(n1))
    }
    "find subgraphs" in {
      val result = Graphs.subGraphFrom(acyclic)(n3)
      result must equalTo(Set(n1, n3))
    }
  }
}