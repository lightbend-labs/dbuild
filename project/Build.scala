import sbt._
import Keys._


object DistributedBuilderBuild extends Build with BuildHelper {

  lazy val root = Project("root", file("."))


  import Dependencies._

  // The component projects...
  def LProject(name: String) = Project(name, file(name))
  lazy val graph = LProject("graph")
  lazy val hashing = LProject("hashing")
  lazy val config = LProject("config") dependsOnRemote(typesafeConfig)

  // Projects relating to distributed builds.
  def DProject(name: String) = Project("d-" + name, file("distributed/"+name))
  lazy val logging = DProject("logging") dependsOnRemote(sbtLogging)
  lazy val dprojects = DProject("projects") dependsOn(graph, config, hashing, logging) dependsOnRemote(sbtIo)
  lazy val dbuild = DProject("build") dependsOn(dprojects)
  lazy val dfiles = DProject("files") dependsOn(hashing) dependsOnRemote(akkaActor)

  lazy val backend = DProject("backend") dependsOn(dfiles, defaultSupport)

  // Projects relating to supprting various tools in distributed builds.
  def SupportProject(name: String) = Project("support-" + name, file("distributed/support/"+name))
  lazy val defaultSupport = SupportProject("default") dependsOn(dprojects, dfiles)
}


// Additional DSL
trait BuildHelper extends Build {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  class RemoteDepHelper(p: Project) {
    def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  }
}
