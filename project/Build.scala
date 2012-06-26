import sbt._
import Keys._

import Dependencies._
  
object DistributedBuilderBuild extends Build with BuildHelper {

  lazy val root = (
    Project("root", file(".")) 
    dependsOn(defaultSupport, dbuild)
    aggregate(graph,hashing,config,logging,dprojects,sbtSupportPlugin, dbuild, backend)
  )

  // The component projects...
  lazy val graph = (
      LibProject("graph")
    )
  lazy val hashing = (
      LibProject("hashing")
    )
  lazy val config = (
      LibProject("config") 
      dependsOnRemote(typesafeConfig)
    )

  // Projects relating to distributed builds.
  lazy val logging = (
      DmodProject("logging")
      dependsOnRemote(sbtLogging, akkaActor, sbtIo)
    )
  lazy val dprojects = (
      DmodProject("projects")
      dependsOn(graph, config, hashing, logging)
      dependsOnRemote(sbtIo)
    )
  lazy val dbuild = (
      DmodProject("build")
      dependsOn(dprojects)
    )

  lazy val backend = (
      DmodProject("backend")
      dependsOn(defaultSupport)
    )

  // Projects relating to supprting various tools in distributed builds.
  lazy val defaultSupport = (
      SupportProject("default") 
      dependsOn(dprojects)
      dependsOnRemote(sbtLauncher)
    )

  // Distributed SBT plugin
  lazy val sbtSupportPlugin = (
    SbtPluginProject("distributed-sbt-plugin", file("distributed/support/sbt-plugin")) 
    dependsOn(dprojects)
  )
}


// Additional DSL
trait BuildHelper extends Build {
  
  def defaultDSettings: Seq[Setting[_]] = Seq(
    organization := "com.typesafe.dsbt",
    scalaVersion := "2.9.2",
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"    
  )
  
  // TODO - Aggregate into a single JAR if possible for easier resolution later...
  def SbtPluginProject(name: String, file: File) = (
      Project(name, file)
      settings(sbtPlugin := true)
      // TODO - Publish maven style, etc.
      settings(defaultDSettings:_*)
    )
  
  /** Create library projects. */
  def LibProject(name: String) = (
      Project(name, file(name)) 
      settings(defaultDSettings:_*)
    )
  /** Create distributed build modules */
  def DmodProject(name: String) = (
      Project("d-" + name, file("distributed/"+name))
      settings(defaultDSettings:_*)
    )
  def SupportProject(name: String) = (
      Project("support-" + name, file("distributed/support/"+name))
      settings(defaultDSettings:_*)
    )
  
  // DSL for adding remote deps like local deps.
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  class RemoteDepHelper(p: Project) {
    def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  }
}
