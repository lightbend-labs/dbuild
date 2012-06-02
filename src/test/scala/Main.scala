import com.typesafe.sbt.distributed._
import meta._
import graph._

object Main {
  
  
  def parseRemote: Build = {
    support.sbt.SbtExtractor.extractMetaData("git://github.com/jsuereth/scala-arm.git")
  }
  
  
  def projects = Seq(
      "git://github.com/jsuereth/scala-arm.git",
      "git://github.com/akka/akka.git",
      "git://github.com/rickynils/scalacheck.git",
      //"git://github.com/etorreborre/scalaz.git#scala-2.9.x",
      "git://github.com/etorreborre/specs2.git",
      //"git://github.com/djspiewak/anti-xml.git",
      "git://github.com/scala-incubator/scala-io.git"
  )
  
  def parseMetas: Seq[Build] = {
      projects map { i => support.sbt.SbtExtractor.extractMetaData(i) }
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
  
}