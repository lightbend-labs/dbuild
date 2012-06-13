import sbt._
import Keys._


object DistributedBuilderBuild extends Build with BuildHelper {

  lazy val root = Project("root", file(".")) dependsOn(defaultSupport, dbuild)

  // TODO - Every project should have specs2 tests, just add it everywhere.

  import Dependencies._

  // The component projects...
  def LProject(name: String) = Project(name, file(name))
  lazy val graph = LProject("graph") dependsOnRemote(specs2)
  lazy val hashing = LProject("hashing")
  lazy val config = LProject("config") dependsOnRemote(typesafeConfig)

  // Projects relating to distributed builds.
  def DProject(name: String) = Project("d-" + name, file("distributed/"+name))
  lazy val logging = DProject("logging") dependsOnRemote(sbtLogging)
  lazy val dprojects = DProject("projects") dependsOn(graph, config, hashing, logging) dependsOnRemote(sbtIo, specs2)
  lazy val dbuild = DProject("build") dependsOn(dprojects) dependsOnRemote(specs2)
  lazy val dfiles = DProject("files") dependsOn(hashing) dependsOnRemote(akkaActor)

  lazy val backend = DProject("backend") dependsOn(dfiles, defaultSupport)

  // Projects relating to supprting various tools in distributed builds.
  def SupportProject(name: String) = Project("support-" + name, file("distributed/support/"+name))
  lazy val defaultSupport = SupportProject("default") dependsOn(dprojects, dfiles)

  // Distributed SBT plugin
  lazy val sbtSupportPlugin = Project("distributed-sbt-plugin", file("distributed/support/sbt-plugin")) settings(sbtPlugin := true)
}


// Additional DSL
trait BuildHelper extends Build {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  class RemoteDepHelper(p: Project) {
    def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  }
}
