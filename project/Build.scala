import sbt._
import Keys._

import Dependencies._
import com.typesafe.packager.PackagerPlugin.Universal  
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.SbtGhPages.{ghpages, GhPagesKeys}
import com.typesafe.sbt.SbtGit.{git, GitKeys}
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub, generatedEpub, sphinxInputs, sphinxPackages, Sphinx }
import com.typesafe.sbt.S3Plugin

object DistributedBuilderBuild extends Build with BuildHelper {

  override def settings = super.settings ++ SbtSupport.buildSettings

  def MyVersion: String = "0.5.3"
  
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
    )
  lazy val hashing = (
      LibProject("hashing")
      dependsOnRemote(typesafeConfig)
    )

  lazy val dmeta = (
      DmodProject("metadata")
      dependsOn(graph, hashing)
      dependsOnRemote(jacks, jackson, typesafeConfig, sbtCollections)
    )

  // Projects relating to distributed builds.
  lazy val logging = (
      DmodProject("logging")
      dependsOnRemote(sbtLogging, sbtIo, sbtLaunchInt)
    )
  lazy val actorLogging = (
      DmodProject("actorLogging")
      dependsOn(logging)
      dependsOnRemote(sbtLogging, akkaActor, sbtIo, sbtLaunchInt)
    )
  lazy val dcore = (
      DmodProject("core")
      dependsOn(dmeta, graph, hashing, logging, drepo)
      dependsOnRemote(sbtIo)
    )
  lazy val dprojects = (
      DmodProject("projects")
      dependsOn(dcore, actorLogging)
      dependsOnRemote(sbtIo)
    )
  lazy val drepo = (
    DmodProject("repo")
    dependsOn(dmeta)
    dependsOnRemote(mvnAether, aetherWagon, dispatch, sbtIo, sbtLaunchInt)
  )
  lazy val dbuild = (
      DmodProject("build")
      dependsOn(dprojects, defaultSupport, drepo, dmeta)
      dependsOnRemote(sbtLaunchInt)
    )

  // Projects relating to supprting various tools in distributed builds.
  lazy val defaultSupport = (
      SupportProject("default") 
      dependsOn(dcore, drepo, dmeta)
      dependsOnRemote(mvnEmbedder, mvnWagon, sbtLaunchInt)
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
    dependsOn(defaultSupport, dmeta)
  )
}


// Additional DSL
trait BuildHelper extends Build {
  
  def MyVersion: String
  
  def defaultDSettings: Seq[Setting[_]] = Seq(
    version := MyVersion,
    organization := "com.typesafe.dbuild",
    scalaVersion := "2.9.2",
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += Resolver.url("typesafe-dbuild-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns),
    publishTo := Some(Resolver.url("typesafe-dbuild-temp", new URL("http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns)),
    publishMavenStyle := false
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
