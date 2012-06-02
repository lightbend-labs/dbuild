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
  
}