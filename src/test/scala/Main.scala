import com.typesafe.sbt.distributed._
import meta._
import graph._

object Main {
  
  def sampleMeta =
    BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git", "framework")
  def sampleMeta2 = 
     BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git#17c750b36c91c59709794c9505e433d7ba5a8f21", "framework")
  def parseRemote =
    (Extractor extract sampleMeta)
  
  
  def projects = Seq(
      BuildConfig("Scala", "scala", "git://github.com/scala/scala.git", ""),
      BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git", "framework"),
      BuildConfig("Scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git", ""),
      BuildConfig("Akka", "sbt", "git://github.com/akka/akka.git", ""),
      BuildConfig("Scalacheck", "sbt", "git://github.com/rickynils/scalacheck.git", ""),
      //"git://github.com/etorreborre/scalaz.git#scala-2.9.x",
      BuildConfig("Specs2", "sbt", "git://github.com/etorreborre/specs2.git", ""),
      //"git://github.com/djspiewak/anti-xml.git",
      BuildConfig("Scala-Io", "sbt", "git://github.com/scala-incubator/scala-io.git#2.10.x", "")
  )
  
  def parseMetas: Seq[Build] = {
      projects map Extractor.extract
  }
  
  def parseIntoGraph: BuildGraph = 
    new BuildGraph(parseMetas)
  
  
  def buildOrder =
    Graphs.topological(parseIntoGraph) map (_.value) reverse
  
    
  def parseIntoDot: String =
    Graphs.toDotFile(parseIntoGraph)(_.config.name)
    
  def writeBuildDot: Unit = {
    val writer = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File("build-deps.dot")))
    try writer.print(parseIntoDot)
    finally writer.close()
  }
  
}