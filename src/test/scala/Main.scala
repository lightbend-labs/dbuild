import distributed._
import project._
import model._
import dependencies._
import graph._
import build._
import distributed.project.resolve.ProjectResolver
object Main {
  
  val resolver = new resolve.AggregateProjectResolver(
      Seq(new support.git.GitProjectResolver))
  val depExtractor = new MultiBuildDependencyExtractor(
      Seq(new support.sbt.SbtDependencyExtractor(),
          support.scala.ScalaDependencyExtractor))
  val extractor = new Extractor(resolver, depExtractor, logging.ConsoleLogger())
  val buildAnalyzer = new SimpleBuildAnalyzer(extractor)
  val buildRunner = new AggregateBuildRunner(Seq(
      support.scala.ScalaBuildRunner,
      new support.sbt.SbtBuildRunner()))
  
  def loadFileIntoDot: Unit = {
    val file = new java.io.File("examplebuild.dsbt")
    val build = DistributedBuildParser parseBuildFile file
    val solved = buildAnalyzer analyze build
    
    val graph = new BuildGraph(solved.builds)
    val dot = Graphs.toDotFile(graph)(_.config.name)
    
    val writer = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File("examplebuild.dot")))
    try writer.print(dot)
    finally writer.close()
    
    import sys.process._
    Process(Seq("dot", "-Tpng", "-o", "examplebuild.png", "examplebuild.dot")).!
  }
  
  
  def runBuildFile = {
    val file = new java.io.File("scala-arm.dsbt")
    val build = DistributedBuildParser parseBuildFile file
    val solved = buildAnalyzer analyze build
    
    val logger = logging.ConsoleLogger()
    
    def runBuild(deps: BuildArtifacts, build: Build) =
      local.ProjectDirs.useDirFor(build.config) { dir =>
         resolver.resolve(build.config, dir)
         val results = buildRunner.runBuild(build, dir, model.BuildArtifacts(Seq.empty), logger)
         BuildArtifacts(deps.artifacts ++ results.artifacts)
      }
    solved.builds.foldLeft(model.BuildArtifacts(Seq.empty))(runBuild)
  }
  
  def runArmBuild: model.BuildArtifacts = {
    val build = buildAnalyzer analyze DistributedBuildConfig(Seq(scalaArm))
    val logger = logging.ConsoleLogger()
    local.ProjectDirs.useDirFor(build.builds.head.config) { dir =>
      resolver.resolve(build.builds.head.config, dir)
      buildRunner.runBuild(build.builds.head, dir, model.BuildArtifacts(Seq.empty), logger)
    }
  }
  
  def scalaArm =
    BuildConfig("Scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git#community-build", "")
    //BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git", "framework")
  def sampleMeta2 = 
     BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git#17c750b36c91c59709794c9505e433d7ba5a8f21", "framework")
  def parseRemote =
    (extractor extract scalaArm)
  
  
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
      projects map extractor.extract
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