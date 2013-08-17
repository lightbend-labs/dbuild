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

object DistributedBuilderBuild extends Build with BuildHelper {

  override def settings = super.settings ++ SbtSupport.buildSettings

  def MyVersion: String = "0.6.4"
  
  lazy val root = (
    Project("root", file(".")) 
    dependsOn(defaultSupport, dbuild, drepo)
    aggregate(graph,hashing,logging,actorLogging,dprojects,dcore,sbtSupportPlugin, dbuild, defaultSupport, drepo, dmeta, ddocs)
    settings(publish := (), version := MyVersion)
  )

  lazy val dist = (
    Project("dist", file("dist")/*, eclipse plugin bombs if we do this: settings = Packaging.settings */) 
    settings(Packaging.settings:_*)
    settings(
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in dbuild, version in dbuild) map Packaging.makeDbuildProps,
      mappings in Universal <+= (target, sourceDirectory, scalaVersion in drepo, version in drepo) map Packaging.makeDRepoProps,
      version := MyVersion
    )
  )

  // The component projects...
  lazy val graph = (
      LibProject("graph")
      settings(crossSettings:_*)
    )
  lazy val hashing = (
      LibProject("hashing")
      dependsOnRemote(typesafeConfig)
      settings(crossSettings:_*)
    )

  lazy val dmeta = (
      DmodProject("metadata")
      dependsOn(graph, hashing)
      dependsOnRemote(jacks, jackson, typesafeConfig, /*sbtCollections,*/ commonsLang)
      settings(crossSettings:_*)
    )

  // Projects relating to distributed builds.
  lazy val logging = (
      DmodProject("logging")
      dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
      settings(crossSettings:_*)
    )
  lazy val actorLogging = (
      DmodProject("actorLogging")
      dependsOn(logging)
      dependsOnRemote(akkaActor)
      dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)      
    )
  lazy val dcore = (
      DmodProject("core")
      dependsOn(dmeta, graph, hashing, logging, drepo)
      dependsOnSbt(sbtIo)
      settings(crossSettings:_*)
    )
  lazy val dprojects = (
      DmodProject("projects")
      dependsOn(dcore, actorLogging)
      dependsOnSbt(sbtIo)
    )
  lazy val drepo = (
    DmodProject("repo")
    dependsOn(dmeta,logging)
    dependsOnRemote(mvnAether, aetherWagon, dispatch)
    dependsOnSbt(sbtIo, sbtLaunchInt)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, version, organization, scalaVersion, streams) map { (dir, version, org, sv, s) =>
        val file = dir / "Defaults.scala"
        if(!dir.isDirectory) dir.mkdirs()
        s.log.info("Generating \"Defaults.scala\" for sbt "+sbtVersion(sv)+" and Scala "+sv)
        IO.write(file, """
package distributed.repo.core

object Defaults {
  val sbtVersion = "%s"
  val version = "%s"
  val org = "%s"
}
""" format (sbtVersion(sv), version, org))
        Seq(file)
      })
      settings(crossSettings:_*)
  )
  lazy val dbuild = (
      DmodProject("build")
      dependsOn(dprojects, defaultSupport, drepo, dmeta)
      dependsOnRemote(aws, uriutil, dispatch, gpgLib)
      dependsOnSbt(sbtLaunchInt)
    )

  // Projects relating to supporting various tools in distributed builds.
  lazy val defaultSupport = (
      SupportProject("default") 
      dependsOn(dcore, drepo, dmeta)
      dependsOnRemote(mvnEmbedder, mvnWagon, javaMail)
      dependsOnSbt(sbtLaunchInt, sbtIvy)
      settings(SbtSupport.settings:_*)
      settings(crossSettings:_*)
    ) 

  // Distributed SBT plugin
  lazy val sbtSupportPlugin = (
    SbtPluginProject("distributed-sbt-plugin", file("distributed/support/sbt-plugin")) 
    dependsOn(defaultSupport, dmeta)
    settings(crossSettings:_*)
      settings(sourceGenerators in Compile <+= (sourceManaged in Compile, scalaVersion, streams) map { (dir, sv, s) =>
        val file = dir / "Update.scala"
        if(!dir.isDirectory) dir.mkdirs()
        s.log.info("Generating \"Update.scala\" for sbt "+sbtVersion(sv)+" and Scala "+sv)
        val where = if (sbtVersion(sv).startsWith("0.12")) "Project" else "Def"
        IO.write(file, """
package com.typesafe.dbuild
object SbtUpdate {
def update[T]: (sbt.%s.ScopedKey[T]) => (T => T) => sbt.%s.Setting[T] = sbt.%s.update[T]
}
""" format (where, where, where))
        Seq(file)
      })
  )
}


// Additional DSL
trait BuildHelper extends Build {
  
  def MyVersion: String

  def sbtVersion(scalaVersion:String) = if (scalaVersion.startsWith("2.9")) "0.12.4" else "0.13.0-RC5"
  
  def defaultDSettings: Seq[Setting[_]] = Seq(
    version := MyVersion,
    organization := "com.typesafe.dbuild",
    scalaVersion := "2.10.2",
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += Resolver.url("typesafe-dbuild-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns),
    publishTo := Some(Resolver.url("typesafe-dbuild-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns)),
    publishArtifact in (Compile, packageSrc) := false,
    publishMavenStyle := false
  )

  def crossSettings: Seq[Setting[_]] = Seq(
    crossScalaVersions := Seq("2.9.2", "2.10.2")
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
    def dependsOnSbt(ms: (String=>ModuleID)*): Project = p.settings(libraryDependencies <++= (scalaVersion) {sv => ms map {_(sbtVersion(sv))}})}

  lazy val ddocs = (Project("d-docs",file("docs"))
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
