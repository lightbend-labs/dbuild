import distributed._
import distributed.build._
import project.build._
import project._
import model._
import dependencies._
import graph._
import distributed.project.resolve.ProjectResolver
import config._
object Main {
  
  val resolver = new resolve.AggregateProjectResolver(
      Seq(new support.git.GitProjectResolver))
  val buildSystems: Seq[BuildSystem] = Seq(new support.sbt.SbtBuildSystem, support.scala.ScalaBuildSystem)
  val depExtractor = new MultiBuildDependencyExtractor(buildSystems)
  val extractor = new Extractor(resolver, depExtractor)
  lazy val logger = logging.ConsoleLogger()
  val buildAnalyzer = new SimpleBuildAnalyzer(extractor)
  val buildRunner = new AggregateBuildRunner(buildSystems)
  
  def loadFileIntoDot: Unit = {
    val file = new java.io.File("examplebuild.dsbt")
    val build = parseFileInto[DistributedBuildConfig](file) getOrElse sys.error("O NOES")
    val solved = buildAnalyzer.analyze(build, logger)
    
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
    val build = parseFileInto[DistributedBuildConfig](file) getOrElse sys.error("O NOES")
    val solved = buildAnalyzer.analyze(build, logger)
    def runBuild(deps: BuildArtifacts, build: Build) =
      local.ProjectDirs.useDirFor(build.config) { dir =>
         resolver.resolve(build.config, dir, logger)
         val results = buildRunner.runBuild(build, dir, deps, logger)
         BuildArtifacts(deps.artifacts ++ results.artifacts, deps.localRepo)
      }
    val repeatable = DistributedBuildConfig(solved.builds map (_.config))
    local.ProjectDirs.userRepoDirFor(repeatable) { localRepo =>
      solved.builds.foldLeft(model.BuildArtifacts(Seq.empty, localRepo))(runBuild)
    }
  }
  
  def runArmBuild: model.BuildArtifacts = {
    val build = buildAnalyzer.analyze(DistributedBuildConfig(Seq(scalaArm)), logger)
    val repeatable = DistributedBuildConfig(build.builds map (_.config))
    local.ProjectDirs.userRepoDirFor(repeatable) { repo =>
      local.ProjectDirs.useDirFor(build.builds.head.config) { dir =>
        resolver.resolve(build.builds.head.config, dir, logger)
        buildRunner.runBuild(build.builds.head, dir, model.BuildArtifacts(Seq.empty, repo), logger)
      }
    }
  }
  
  def scalaArm =
    BuildConfig("Scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git#community-build")
    //BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git", "framework")
  /*def sampleMeta2 = 
     BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git#17c750b36c91c59709794c9505e433d7ba5a8f21", "framework")*/
  def parseRemote =
    (extractor.extract(scalaArm, logger))
  
  
  def projects = Seq(
      BuildConfig("Scala", "scala", "git://github.com/scala/scala.git"),
      //BuildConfig("Play2", "sbt", "git://github.com/playframework/Play20.git", "framework"),
      BuildConfig("Scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git"),
      BuildConfig("Akka", "sbt", "git://github.com/akka/akka.git"),
      BuildConfig("Scalacheck", "sbt", "git://github.com/rickynils/scalacheck.git"),
      //"git://github.com/etorreborre/scalaz.git#scala-2.9.x",
      BuildConfig("Specs2", "sbt", "git://github.com/etorreborre/specs2.git"),
      //"git://github.com/djspiewak/anti-xml.git",
      BuildConfig("Scala-Io", "sbt", "git://github.com/scala-incubator/scala-io.git#2.10.x")
  )
  
  def parseMetas: Seq[Build] = {
      projects map (p => extractor.extract(p, logger))
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