import com.typesafe.sbt.distributed._
import meta._
import graph._

object Main {
  
  def sampleMeta =
    BuildConfig("sbt", "git://github.com/playframework/Play20.git", "framework")
  def sampleMeta2 = 
     BuildConfig("sbt", "git://github.com/playframework/Play20.git#17c750b36c91c59709794c9505e433d7ba5a8f21", "framework")
  def parseRemote: ExtractedBuildMeta =
    (Extractor extract sampleMeta)
  
  
  def projects = Seq(
      //BuildConfig("sbt", "git://github.com/playframework/Play20.git", "framework"),
      BuildConfig("sbt", "git://github.com/jsuereth/scala-arm.git", ""),
      BuildConfig("sbt", "git://github.com/akka/akka.git", ""),
      BuildConfig("sbt", "git://github.com/rickynils/scalacheck.git", ""),
      //"git://github.com/etorreborre/scalaz.git#scala-2.9.x",
      BuildConfig("sbt", "git://github.com/etorreborre/specs2.git", ""),
      //"git://github.com/djspiewak/anti-xml.git",
      BuildConfig("sbt", "git://github.com/scala-incubator/scala-io.git", "")
  )
  
  def parseMetas: Seq[Build] = {
      projects map { i => new Build(i, Extractor extract i ) }
  }
  
  def parseIntoGraph: BuildGraph = 
    new BuildGraph(parseMetas)
  
  
  def buildOrder =
    Graphs.topological(parseIntoGraph) map (_.value) reverse
  
    
  def parseIntoDot: String =
    Graphs.toDotFile(parseIntoGraph)(_.config.uri)
    
  def writeBuildDot: Unit = {
    val writer = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File("build-deps.dot")))
    try writer.print(parseIntoDot)
    finally writer.close()
  }
  
}