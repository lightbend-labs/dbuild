import com.typesafe.sbt.distributed._
import meta._
import graph._

object Main {
  
  
  def parseRemote: Build = {
    support.sbt.SbtExtractor.extractMetaData("git://github.com/jsuereth/scala-arm.git")
  }
  
  
  def projects = Seq(
      "git://github.com/jsuereth/scala-arm.git",
      "git://github.com/akka/akka.git"
  )
  
  def parseMetas: Seq[Build] = {
      projects map support.sbt.SbtExtractor.extractMetaData
  }
  
  def parseIntoGraph: BuildGraph = 
    new BuildGraph(parseMetas)
  
  
  def buildOrder =
    Graphs.topological(parseIntoGraph) map (_.value) reverse
  
    
  def parseIntoDot: String =
    Graphs.toDotFile(parseIntoGraph)(_.uri)
    
  def writeBuildDot: Unit = {
    val writer = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File("build-deps.dot")))
    try writer.print(parseIntoDot)
    finally writer.close()
  }
    
  def testCyclic: (Graphs.type, Graph[String, Nothing], Graph[String, Nothing]) = {
    val n1 = SimpleNode("node1")
    val n2 = SimpleNode("node2")
    val n3 = SimpleNode("node3")
    val e1 = Seq(EmptyEdge(n1, n2), EmptyEdge(n1, n3))
    val e2 = Seq(EmptyEdge(n2, n3))
    val e3 = Seq(EmptyEdge(n3, n1))
    (Graphs, SimpleGraph[String, Nothing](
        nodes = Set(n1,n2,n3),
        _edges = Map(
          n1 -> e1,
          n2 -> e2,
          n3 -> e3
        )),
      SimpleGraph[String, Nothing](
        nodes = Set(n1,n2,n3),
        _edges = Map(
          n3 -> e3
        ))
     )
  }
}