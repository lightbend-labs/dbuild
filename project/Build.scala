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
import bintray.BintrayKeys._

object DBuilderBuild extends Build with BuildHelper {

  override def settings = super.settings ++ SbtSupport.buildSettings

  def MyVersion: String = "0.9.5"
  
  lazy val root = (
    Proj("root") 
    aggregate(graph, hashing, logging, actorLogging, proj, actorProj, deploy,
      core, plugin, build, support, supportGit, repo, metadata, docs, dist, indexmeta)
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
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in build, version in build) map Packaging.makeDBuildProps,
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in repo, version in repo) map Packaging.makeDRepoProps,
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
      dependsOnRemote(jacks, jackson, typesafeConfig, commonsLang, aws, uriutil, dispatch, commonsIO, jsch)
      dependsOnSbt(sbtLogging, sbtIo)
    )

  lazy val indexmeta = (
      Proj("indexmeta")
    )

  lazy val metadata = (
      Proj("metadata")
      dependsOn(graph, hashing, indexmeta, deploy)
      dependsOnRemote(jacks, jackson, typesafeConfig, /*sbtCollections,*/ commonsLang)
    )

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
  lazy val core = (
      Proj("core")
      dependsOnRemote(javaMail)
      dependsOn(metadata, graph, hashing, logging, repo)
      dependsOnSbt(sbtIo)
    )
  lazy val proj = (
      Proj("proj")
      dependsOn(core, repo, logging)
      dependsOnRemote(javaMail, commonsIO)
      dependsOnSbt(sbtIo, sbtIvy)
    )
  lazy val actorProj = (
      Proj("actorProj")
      dependsOn(core, actorLogging, proj)
      dependsOnSbt(sbtIo, sbtIvy)
      settings(skip210:_*)
    )
  lazy val repo = (
    Proj("repo")
    dependsOn(metadata, logging)
    dependsOnRemote(mvnAether, dispatch, aether, aetherApi, aetherSpi, aetherUtil, aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, aetherWagon, mvnAether)
    dependsOnSbt(sbtIo, sbtLaunchInt)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, version, organization, scalaVersion, streams) map { (dir, version, org, sv, s) =>
        val file = dir / "Defaults.scala"
        if(!dir.isDirectory) dir.mkdirs()
        s.log.info("Generating \"Defaults.scala\" for sbt "+sbtVer(sv)+" and Scala "+sv)
        IO.write(file, """
package com.typesafe.dbuild.repo.core

object Defaults {
  val version = "%s"
  val org = "%s"
  val hash = "%s"
}
""" format (version, org, Process("git log --pretty=format:%H -n 1").lines.head))
        Seq(file)
      })
  )
  lazy val build = (
      Proj("build")
      dependsOn(actorProj, support, supportGit, repo, metadata, deploy, proj)
      dependsOnRemote(aws, uriutil, dispatch, gpgLib, jsch, oro, scallop, commonsLang)
      dependsOnSbt(sbtLaunchInt, sbtLauncher)
      settings(skip210:_*)
      settings(SbtSupport.settings:_*)
      settings(
        // We hook the testLoader of it to make sure all the it tasks have a legit sbt plugin to use.
        // Technically, this just pushes every project.  We could outline just the plugin itself, but for now
        // we don't care that much.
        testLoader in IntegrationTest := {
          val ignore = publishLocal.all(ScopeFilter(inAggregates(LocalRootProject, includeRoot=false))).value
          (testLoader in IntegrationTest).value
        },
        parallelExecution in IntegrationTest := false
      )
    )

  lazy val support = (
      Proj("support") 
      dependsOn(core, repo, metadata, proj % "compile->compile;it->compile", logging % "it")
      dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, aether, aetherApi, aetherSpi, aetherUtil, aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, slf4jSimple)
      dependsOnSbt(sbtLaunchInt, sbtIvy)
      settings(SbtSupport.settings:_*)
      settings(
        // We hook the testLoader of it to make sure all the it tasks have a legit sbt plugin to use.
        // Technically, this just pushes every project.  We could outline just the plugin itself, but for now
        // we don't care that much.
        testLoader in IntegrationTest := {
          val ignore = publishLocal.all(ScopeFilter(inAggregates(LocalRootProject, includeRoot=false))).value
          (testLoader in IntegrationTest).value
        },
        parallelExecution in IntegrationTest := false
      )
    ) 
  // A separate support project for git/jgit
  lazy val supportGit = (
      Proj("supportGit") 
      dependsOn(core, repo, metadata, proj, support)
      dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, jgit)
      dependsOnSbt(sbtLaunchInt, sbtIvy)
      settings(SbtSupport.settings:_*)
      settings(skip210:_*)
    ) 

  // SBT plugin
  lazy val plugin = (
    Proj("plugin") 
    settings(sbtPlugin := true)
    dependsOn(support, metadata)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, scalaVersion, streams) map { (dir, sv, s) =>
        val file = dir / "Update.scala"
        if(!dir.isDirectory) dir.mkdirs()
        s.log.info("Generating \"Update.scala\" for sbt "+sbtVer(sv)+" and Scala "+sv)
        val where = if (sbtVer(sv).startsWith("0.12")) "Project" else "Def"
        IO.write(file, """
package com.typesafe.dbuild.plugin
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
    publishMavenStyle := false
  )
  /** Create a project. */
  def Proj(name: String) = (
      Project(name, file(if (name=="root") "." else name))
      configs( IntegrationTest )
      settings( Defaults.itSettings : _*)
      settings(defaultDSettings:_*)
      settings(
        licenses += ("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0"))
      )
      settings(
        bintrayReleaseOnPublish := false,
        licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0")),
        bintrayOrganization := Some("typesafe"),
        bintrayRepository := "ivy-releases",
        bintrayPackage := "dbuild"
      )
    )
  
  // DSL for adding remote deps like local deps.
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  class RemoteDepHelper(p: Project) {
    def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
    def dependsOnSbt(ms: (String=>ModuleID)*): Project = p.settings(libraryDependencies <++= (scalaVersion) {sv => ms map {_(sbtVer(sv))}})
    def dependsOnAkka(): Project = p.settings(libraryDependencies <+= (scalaVersion) {sv => if (sv.startsWith("2.9")) akkaActor29 else akkaActor210})
  }

  lazy val docs = (Proj("docs")
      settings(defaultDSettings ++ site.settings ++ site.sphinxSupport() ++
      ghpages.settings ++ Seq(
//      enableOutput in generatePdf in Sphinx := true,
//      enableOutput in generateEpub in Sphinx := true,
        git.remoteRepo := "git@github.com:typesafehub/dbuild.git",
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
