import Dependencies._

def MyVersion: String = "0.9.7-SNAPSHOT"

def SubProj(name: String) = (
  Project(name, file(if (name=="root") "." else name))
  configs( IntegrationTest )
  settings( Defaults.itSettings : _*)
  settings(
    version := MyVersion,
    organization := "com.typesafe.dbuild",
    selectScalaVersion,
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    publishMavenStyle := false,
    licenses += ("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0")),
    licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0")),
    bintrayReleaseOnPublish := false,
    bintrayOrganization := Some("typesafe"),
    bintrayRepository := "ivy-releases",
    bintrayPackage := "dbuild"
  )
)

import RemoteDepHelper._

def skip211 = Seq(
      skip in compile := scalaVersion.value.startsWith("2.11"),
      sources in doc in Compile :=
        { if((skip in compile).value) List() else (sources in doc in Compile).value }
     )

def selectScalaVersion =
  scalaVersion := {
    val sb = (sbtVersion in sbtPlugin).value
    if (sb.startsWith("0.13")) "2.10.6" else "2.11.8"
  }

lazy val root = (
  SubProj("root")
  aggregate(adapter, graph, hashing, logging, actorLogging, proj, actorProj, deploy,
            core, plugin, build, support, supportGit, repo, metadata, docs, dist, indexmeta)
  settings(publish := (), publishLocal := (), version := MyVersion)
  //settings(CrossPlugin.crossBuildingSettings:_*)
  //settings(CrossBuilding.crossSbtVersions := Seq("0.13","1.0.0-M4"), selectScalaVersion)
  settings(commands += Command.command("release") { state =>
    "clean" :: "publish" :: state
  })
)

// This subproject only has dynamically
// generated source files, used to adapt
// the source file to sbt 0.13/1.0
lazy val adapter = (
  SubProj("adapter")
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt, sbtIvy, sbtSbt)
  dependsOnRemote(zincIf211:_*)
  settings(sourceGenerators in Compile += task {
    val dir = (sourceManaged in Compile).value
    val fileName = "Default.scala"
    val file = dir / fileName
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
    IO.write(file, (
"""
package com.typesafe.dbuild.adapter
object Defaults {
  val version = "%s"
  val org = "%s"
  val hash = "%s"
}
""" format (version.value, organization.value, scala.sys.process.Process("git log --pretty=format:%H -n 1").lines.head))

)
    Seq(file)
  })
)

lazy val graph = (
  SubProj("graph")
)

lazy val hashing = (
  SubProj("hashing")
  dependsOnRemote(typesafeConfig)
)

lazy val indexmeta = (
  SubProj("indexmeta")
)

lazy val logging = (
  SubProj("logging")
  dependsOn(adapter,graph)
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
)

lazy val actorLogging = (
  SubProj("actorLogging")
  dependsOn(logging)
  dependsOnRemote(akkaActor)
  dependsOnSbt(sbtLogging, sbtIo, sbtLaunchInt)
  settings(skip211:_*)
)

lazy val metadata = (
  SubProj("metadata")
  dependsOn(graph, hashing, indexmeta, deploy)
  dependsOnRemote(jackson, typesafeConfig, commonsLang, jacks)
)

lazy val repo = (
  SubProj("repo")
  dependsOn(adapter, metadata, logging)
  dependsOnRemote(mvnAether, dispatch, aether, aetherApi, aetherSpi, aetherUtil, aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, aetherWagon, mvnAether)
  dependsOnSbt(sbtIo, sbtLaunchInt)
)

lazy val core = (
  SubProj("core")
  dependsOnRemote(javaMail)
  dependsOn(adapter,metadata, graph, hashing, logging, repo)
  dependsOnSbt(sbtIo)
)

lazy val proj = (
  SubProj("proj")
  dependsOn(core, repo, logging)
  dependsOnRemote(javaMail, commonsIO)
  dependsOnSbt(sbtIo, sbtIvy)
)

lazy val actorProj = (
  SubProj("actorProj")
  dependsOn(core, actorLogging, proj)
  dependsOnSbt(sbtIo, sbtIvy)
  settings(skip211:_*)
)

lazy val support = (
  SubProj("support")
  dependsOn(core, repo, metadata, proj % "compile->compile;it->compile", logging % "it")
  dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, aether, aetherApi, aetherSpi, aetherUtil,
                  aetherImpl, aetherConnectorBasic, aetherFile, aetherHttp, slf4jSimple)
  dependsOnSbt(sbtLaunchInt, sbtIvy)
  settings(SbtSupport.buildSettings:_*)
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
  SubProj("supportGit") 
  dependsOn(core, repo, metadata, proj, support)
  dependsOnRemote(mvnEmbedder, mvnWagon, javaMail, jgit)
  dependsOnSbt(sbtLaunchInt, sbtIvy)
  settings(SbtSupport.buildSettings:_*)
  settings(SbtSupport.settings:_*)
  settings(skip211:_*)
)

// SBT plugin
lazy val plugin = (
  SubProj("plugin") 
  settings(sbtPlugin := true)
  dependsOn(adapter, support, metadata)
  dependsOnSbt(sbtIo)
)

lazy val dist = (
  SubProj("dist")
  settings(Packaging.settings(build,repo):_*)
)

lazy val deploy = (
  SubProj("deploy")
  dependsOn(adapter)
  dependsOnRemote(jackson, typesafeConfig, commonsLang, aws, uriutil, dispatch, commonsIO, jsch, jacks)
  dependsOnSbt(sbtLogging, sbtIo)
)

lazy val build = (
  SubProj("build")
  dependsOn(actorProj, support, supportGit, repo, metadata, deploy, proj)
  dependsOnRemote(aws, uriutil, dispatch, jsch, oro, scallop, commonsLang)
  dependsOnRemote(gpgLibIf210:_*)
  dependsOnSbt(sbtLaunchInt, sbtLauncher)
  settings(skip211:_*)
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

lazy val docs = (
  SubProj("docs")
  settings(DocsSupport.settings:_*)
)

