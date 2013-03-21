import sbt._
import Keys._

import Dependencies._
import com.typesafe.packager.PackagerPlugin.Universal  
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.SbtGhPages.ghpages
import com.typesafe.sbt.SbtGit.{git, GitKeys}
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub, generatedEpub, sphinxInputs, sphinxPackages, Sphinx }
import com.typesafe.sbt.S3Plugin

object DistributedBuilderBuild extends Build with BuildHelper {

  override def settings = super.settings ++ SbtSupport.buildSettings

  def MyVersion: String = "0.4.3-SNAPSHOT"
  
  lazy val root = (
    Project("root", file(".")) 
    dependsOn(defaultSupport, dbuild, drepo)
    aggregate(graph,hashing,config,logging,dprojects,sbtSupportPlugin, dbuild, backend, defaultSupport, drepo, dmeta, ddocs)
    settings(publish := (), version := MyVersion)
  )

  lazy val dist = (
    Project("dist", file("dist")/*, eclipse plugin bombs if we do this: settings = Packaging.settings */) 
    settings(Packaging.settings:_*)
    settings(
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in dbuild, version in dbuild) map Packaging.makeDsbtProps,
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in drepo, version in drepo) map Packaging.makeDRepoProps,
      version := MyVersion
    )
  )

  // The component projects...
  lazy val graph = (
      LibProject("graph")
    )
  lazy val hashing = (
      LibProject("hashing")
      dependsOnRemote(akkaActor)
    )
  lazy val config = (
      LibProject("config") 
      dependsOnRemote(typesafeConfig, sbtCollections)
    )

  lazy val dmeta = (
      DmodProject("metadata")
      dependsOn(graph, config, hashing)
    )

  // Projects relating to distributed builds.
  lazy val logging = (
      DmodProject("logging")
      dependsOnRemote(sbtLogging, akkaActor, sbtIo, sbtLaunchInt)
    )
  lazy val dprojects = (
      DmodProject("projects")
      dependsOn(dmeta, graph, config, hashing, logging, drepo)
      dependsOnRemote(sbtIo)
    )
  lazy val drepo = (
    DmodProject("repo")
    dependsOn(dmeta)
    dependsOnRemote(mvnAether, aetherWagon, dispatch, sbtIo, sbtLaunchInt)
  )
  lazy val dbuild = (
      DmodProject("build")
      dependsOn(dprojects, defaultSupport, drepo, config)
      dependsOnRemote(sbtLaunchInt)
    )

  lazy val backend = (
      DmodProject("backend")
      dependsOn(defaultSupport)
    )

  // Projects relating to supprting various tools in distributed builds.
  lazy val defaultSupport = (
      SupportProject("default") 
      dependsOn(dprojects, drepo)
      dependsOnRemote(mvnEmbedder, mvnWagon)
      settings(SbtSupport.settings:_*)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, version, organization) map { (dir, version, org) =>
        val file = dir / "Defaults.scala"
        if(!dir.isDirectory) dir.mkdirs()
        IO.write(file, """
package distributed.support.sbt

object Defaults {
  val sbtVersion = "%s"
  val version = "%s"
  val org = "%s"
}
""" format (Dependencies.sbtVersion, version, org))
        Seq(file)
      })
    ) 

  // Distributed SBT plugin
  lazy val sbtSupportPlugin = (
    SbtPluginProject("distributed-sbt-plugin", file("distributed/support/sbt-plugin")) 
    dependsOn(dprojects, defaultSupport)
  )
}


// Additional DSL
trait BuildHelper extends Build {
  
  def MyVersion: String
  
  def defaultDSettings: Seq[Setting[_]] = Seq(
    version := MyVersion,
    organization := "com.typesafe.dsbt",
    scalaVersion := "2.9.2",
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    publishMavenStyle := false,
    publishTo := Some(Resolver.url("typesafe-dsbt-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns))
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

  lazy val ddocs = (Project("d-docs",file("docs"))
    settings(defaultDSettings ++ site.settings ++ site.sphinxSupport() ++
      ghpages.settings ++ Seq(
//      enableOutput in generatePdf in Sphinx := true,
//      enableOutput in generateEpub in Sphinx := true,
        git.remoteRepo := "git@github.com:cunei/distributed-build.git",
      publish := (),
      publishLocal := ()
    ):_*))

}
