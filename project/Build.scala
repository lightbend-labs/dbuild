import sbt._
import Keys._

import Dependencies._
import com.typesafe.sbt.SbtNativePackager.Universal  
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.SbtGhPages.{ghpages, GhPagesKeys}
import com.typesafe.sbt.SbtGit.{git, GitKeys}
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub, generatedEpub, sphinxInputs, sphinxPackages, Sphinx }
import com.typesafe.sbt.S3Plugin
import net.virtualvoid.sbt.cross.CrossPlugin

object DistributedBuilderBuild extends Build with BuildHelper {

  override def settings = super.settings ++ SbtSupport.buildSettings

  def MyVersion: String = "0.9.2-SNAPSHOT"
  
  lazy val root = (
    Proj("root") 
    aggregate(graph,hashing,logging,actorLogging,Projects,dactorProjects,dcore,sbtSupportPlugin, dbuild, defaultSupport, gitSupport, drepo, dmeta, ddocs, dist, dindex)
    settings(publish := (), publishLocal := (), version := MyVersion)
    settings(CrossPlugin.crossBuildingSettings:_*)
    settings(CrossBuilding.crossSbtVersions := Seq("0.12","0.13"), selectScalaVersion)
    settings(commands += Command.command("release") { state =>
      "clean" :: "publish" :: state
    })
  )

  lazy val dist = (
    Proj("dist")
    /*, eclipse plugin bombs if we do this: settings = Packaging.settings */
    settings(Packaging.settings:_*)
    settings(
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in dbuild, version in dbuild) map Packaging.makeDbuildProps,
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in drepo, version in drepo) map Packaging.makeDRepoProps,
      version := MyVersion,
      selectScalaVersion,
      // disable the publication of artifacts in dist if 2.10
      // (we only retain the correct launcher, which is the
      // one generated using 2.9)
      // This is a pretty ugly hack, but it is quite difficult to prevent sbt from
      // skipping publishing completely (including the ivy file) upon a given condition.
      publishConfiguration <<= (publishConfiguration,scalaVersion) map { (p,sv) => 
        if (sv.startsWith("2.10")) new sbt.PublishConfiguration(None,p.resolverName,Map.empty,Seq.empty,p.logging) else p}
    )
  )

  // The component projects...
  lazy val graph = (
      Proj("graph")
    )
  lazy val hashing = (
      Proj("hashing")
      dependsOnRemote(typesafeConfig)
    )
  lazy val deploy = (
      Proj("deploy")
      dependsOnRemote(jacks, jackson, typesafeConfig, commonsLang, aws, uriutil, dispatch, commonsIO,  jsch)
      dependsOnSbt(sbtLogging, sbtIo)
    )

  lazy val dindex = (
      Proj("indexmeta")
    )

  lazy val dmeta = (
      Proj("metadata")
      dependsOn(graph, hashing, dindex, deploy)
      dependsOnRemote(jacks, jackson, typesafeConfig, /*sbtCollections,*/ commonsLang)
    )

  // Projs relating to distributed builds.
  lazy val logging = (
      Proj("logging")
      dependsOn(graph)
      dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
    )
  lazy val actorLogging = (
      Proj("actorLogging")
      dependsOn(logging)
      dependsOnAkka()
      dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
      settings(skip210:_*)
    )
  lazy val dcore = (
      Proj("core")
      dependsOnRemote(javaMail)
      dependsOn(dmeta, graph, hashing, logging, drepo)
      dependsOnSbt(sbtIo)
    )
  lazy val Projects = (
      Proj("projects")
      dependsOn(dcore, drepo, logging)
      dependsOnRemote(javaMail, commonsIO)
      dependsOnSbt(sbtIo, sbtIvy)
    )
  lazy val dactorProjects = (
      Proj("actorProjects")
      dependsOn(dcore, actorLogging, Projects)
      dependsOnSbt(sbtIo, sbtIvy)
      settings(skip210:_*)
    )
  lazy val drepo = (
    Proj("repo")
    dependsOn(dmeta, logging)
    dependsOnRemote(mvnAether, aetherWagon, dispatch)
    dependsOnSbt(sbtIo, sbtLaunchInt)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, version, organization, scalaVersion, streams) map { (dir, version, org, sv, s) =>
        val file = dir / "Defaults.scala"
        if(!dir.isDirectory) dir.mkdirs()
        s.log.info("Generating \"Defaults.scala\" for sbt "+sbtVer(sv)+" and Scala "+sv)
        IO.write(file, """
package distributed.repo.core

object Defaults {
  val version = "%s"
  val org = "%s"
}
""" format (version, org))
        Seq(file)
      })
  )
  lazy val dbuild = (
      Proj("build")
      dependsOn(dactorProjects, defaultSupport, gitSupport, drepo, dmeta, deploy)
      dependsOnRemote(aws, uriutil, dispatch, gpgLib, jsch, oro, scallop, commonsLang)
      dependsOnSbt(sbtLaunchInt, sbtLauncher)
      settings(skip210:_*)
    )

  // Projects relating to supporting various tools in distributed builds.
  lazy val defaultSupport = (
      Proj("support") 
      dependsOn(dcore, drepo, dmeta, Projects)
      dependsOnRemote(mvnEmbedder, mvnWagon, javaMail)
      dependsOnSbt(sbtLaunchInt, sbtIvy)
      settings(SbtSupport.settings:_*)
    ) 
  // A separate support project for git/jgit
  lazy val gitSupport = (
      Proj("supportGit") 
      dependsOn(dcore, drepo, dmeta, Projects, defaultSupport)
      dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, jgit)
      dependsOnSbt(sbtLaunchInt, sbtIvy)
      settings(SbtSupport.settings:_*)
      settings(skip210:_*)
    ) 

  // Distributed SBT plugin
  lazy val sbtSupportPlugin = (
    Proj("sbtPlugin") 
    settings(sbtPlugin := true)
    dependsOn(defaultSupport, dmeta)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, scalaVersion, streams) map { (dir, sv, s) =>
        val file = dir / "Update.scala"
        if(!dir.isDirectory) dir.mkdirs()
        s.log.info("Generating \"Update.scala\" for sbt "+sbtVer(sv)+" and Scala "+sv)
        val where = if (sbtVer(sv).startsWith("0.12")) "Project" else "Def"
        IO.write(file, """
package com.typesafe.dbuild
object SbtUpdate {
def update[T]: (sbt.%s.ScopedKey[T]) => (T => T) => sbt.%s.Setting[T] = sbt.%s.update[T]
}
""" format (where, where, where))
        Seq(file)
     },
     CrossBuilding.crossSbtVersions := Seq("0.12","0.13")
   )
   settings(CrossPlugin.crossBuildingSettings:_*)
  )
}



// Additional DSL
trait BuildHelper extends Build {
  
  def MyVersion: String

  // for dependencies
  def sbtVer(scalaVersion:String) = if (scalaVersion.startsWith("2.9")) sbtVersion12 else sbtVersion13

  def selectScalaVersion =
    scalaVersion <<= (sbtVersion in sbtPlugin).apply( sb => if (sb.startsWith("0.12")) "2.9.2" else "2.10.2" )

  def skip210 = 
    Seq(skip in compile <<= scalaVersion.map(_.startsWith("2.10")),
        sources in doc in Compile <<= (sources in doc in Compile,skip in compile).map( (c,s) =>
          if(s) List() else c ) )
  
  def defaultDSettings: Seq[Setting[_]] = Seq(
    version := MyVersion,
    organization := "com.typesafe.dbuild",
    selectScalaVersion,
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += Resolver.url("typesafe-dbuild-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns),
    publishTo := Some(Resolver.url("typesafe-dbuild-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns)),
    publishArtifact in (Compile, packageSrc) := false,
    publishMavenStyle := false
  )

  /** Create a project. */
  def Proj(name: String) = (
      Project(name, file(if (name=="root") "." else name))
      settings(defaultDSettings:_*)
    )
  
  // DSL for adding remote deps like local deps.
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  class RemoteDepHelper(p: Project) {
    def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
    def dependsOnSbt(ms: (String=>ModuleID)*): Project = p.settings(libraryDependencies <++= (scalaVersion) {sv => ms map {_(sbtVer(sv))}})
    def dependsOnAkka(): Project = p.settings(libraryDependencies <+= (scalaVersion) {sv => if (sv.startsWith("2.9")) akkaActor29 else akkaActor210})
  }

  lazy val ddocs = (Project("docs",file("docs"))
    settings(defaultDSettings ++ site.settings ++ site.sphinxSupport() ++
      ghpages.settings ++ Seq(
//      enableOutput in generatePdf in Sphinx := true,
//      enableOutput in generateEpub in Sphinx := true,
        git.remoteRepo := "git@github.com:typesafehub/distributed-build.git",
      GhPagesKeys.synchLocal <<= Docs.synchLocalImpl,
      publish := (),
      publishLocal := ()
    ):_*))


    // based on the related sbt source code
    object Docs {
        val VersionPattern = """(\d+)\.(\d+)\.(\d+)(-.+)?""".r.pattern

        def synchLocalImpl = (mappings in GhPagesKeys.synchLocal, GhPagesKeys.updatedRepository, version, isSnapshot, streams) map {
            (mappings, repo, v, snap, s) =>
                val versioned = repo / v
                if(snap) {
                        s.log.info("Replacing docs for previous snapshot in: " + versioned.getAbsolutePath)
                        IO.delete(versioned)
                } else if(versioned.exists) {
                        s.log.warn("Site for " + v + " was already in: " + versioned.getAbsolutePath)
                        s.log.info("Replacing previous docs...")
                        IO.delete(versioned)
                }
                IO.copy(mappings map { case (file, target) => (file, versioned / target) })
                IO.touch(repo / ".nojekyll")
                IO.write(repo / "versions.js", versionsJs(sortVersions(collectVersions(repo))))
                if (!snap) IO.write(repo / "index.html",
                                    """|<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
                                       |<HTML><HEAD><title>dbuild</title>
                                       |<meta http-equiv="REFRESH" content="0;url=""".stripMargin+v+
                                    """|/index.html"></HEAD>
                                       |<BODY><p><a href=""".stripMargin+"\""+v+"""/index.html"> </a></p></BODY>
                                       |</HTML>""".stripMargin)
                s.log.info("Copied site to " + versioned)
                repo
        }
        def versionsJs(vs: Seq[String]): String = "var availableDocumentationVersions = " + vs.mkString("['", "', '", "']")
        // names of all directories that are explicit versions
        def collectVersions(base: File): Seq[String] = (base * versionFilter).get.map(_.getName)
        def sortVersions(vs: Seq[String]): Seq[String] = vs.sortBy(versionComponents).reverse
        def versionComponents(v: String): Option[(Int,Int,Int,Option[String])] = {
                val m = VersionPattern.matcher(v)
                if(m.matches())
                        Some( (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt, Option(m.group(4))) )
                else
                        None
        }
        def versionFilter = new PatternFilter(VersionPattern) && DirectoryFilter

    }

}
