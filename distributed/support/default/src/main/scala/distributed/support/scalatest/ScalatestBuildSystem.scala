package distributed
package support
package scalatest

import project.BuildSystem
import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import _root_.sbt.IO
import logging.Logger
import sys.process._


case class MavenDep(dep: ProjectRef, version: String, optional: Boolean = false, scope: String = "compile")

/** Implementation of the Scala  build system. */
object ScalatestBuildSystem extends BuildSystem {
  val name: String = "scalatest"  
  
  def extractDependencies(config: BuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    // TODO - don't HardCode
    ExtractedBuildMeta("", 
        Seq(
          Project("scalatest", group, 
              Seq(scalatest),
              Seq(
                scalaLibrary,
                scalaCompiler,
                testInterface,
                antlrStringTemplate,
                scalacheck,
                easymockclassext,
                jmock,
                mockito,
                testng,
                guice,
                junit,
                cobertura,
                commonsIo
              ))
        ))
  }

  def runBuild(project: Build, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    val scalaVersion = readScalaVersion(dependencies)
    // We need to blow away pom_template.xml so scalatest publishes the way we want it to.
    // Maybe in the future we read what's there to do this.  For now, let's live dangerously.
    val pomTemplateFile = dir / "pom_template.xml"
    val pomTemplateXmlString = makePomTemplate(dependencies)
    IO.write(pomTemplateFile, pomTemplateXmlString)
    
    Process(Seq("ant", "compile"), Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Unable to compile scalatest project!")
    }
    // TODO - Add ant
    dependencies
  }

    
    
    
  private def readScalaVersion(dependencies: BuildArtifacts): String = {
    val version = (for {
      artifact <- dependencies.artifacts.view
      if artifact.dep == scalaLibrary
    } yield artifact.version).headOption
    version getOrElse sys.error("Could not find scala version!")
  } 
    
  private[this] def group = "org.scalatest"
  private[this] def scalatest = ProjectRef("scalatest", group)
  private[this] def scalaOrg = "org.scala-lang"
  private[this] def scalaLibrary = ProjectRef("scala-library", scalaOrg)
  private[this] def scalaActors = ProjectRef("scala-actors", scalaOrg)
  private[this] def scalaReflect = ProjectRef("scala-reflect", scalaOrg)
  private[this] def scalaCompiler = ProjectRef("scala-compiler", scalaOrg)
  private[this] def testInterface = ProjectRef("test-interface", "org.scala-tools.testing")
  private[this] def antlrStringTemplate = ProjectRef("stringtemplate", "org.antlr")
  private[this] def scalacheck = ProjectRef("scalacheck", "org.scalacheck")
  private[this] def easymockclassext = ProjectRef("easymockclassextension", "org.easymock")
  private[this] def jmock = ProjectRef("jmock-legacy", "org.jmock")
  private[this] def mockito = ProjectRef("mockito-all", "org.mockito")
  private[this] def testng = ProjectRef("testng", "org.testng")
  private[this] def guice = ProjectRef("guice", "com.google.inject")
  private[this] def junit = ProjectRef("junit", "junit")
  private[this] def cobertura = ProjectRef("cobertura", "net.sourceforge.cobertura")
  private[this] def commonsIo = ProjectRef("commons-io", "org.apache.commons")
  
  private[this] def defaultArtifacts = Seq(
    MavenDep(scalaLibrary, ""),
    MavenDep(scalaActors, ""),
    MavenDep(scalaReflect, ""),
    MavenDep(scalaCompiler, ""),
    MavenDep(testInterface, "0.5", optional = true),
    MavenDep(antlrStringTemplate, "3.2", optional = true),
    MavenDep(scalacheck, "1.8", optional = true),
    MavenDep(easymockclassext, "3.1", optional = true),
    MavenDep(jmock, "2.5.1", optional = true),
    MavenDep(mockito, "1.9.0", optional = true),
    MavenDep(testng, "6.3.1", optional = true),
    MavenDep(guice, "3.0", optional = true),
    MavenDep(junit, "4.10", optional = true),
    MavenDep(cobertura, "1.9.1", scope = "test"),
    MavenDep(commonsIo, "1.3.2", scope = "test")
  )
  
  def fixArtifacts(buildArts: BuildArtifacts): Seq[MavenDep] = {
    def fixDep(mvn: MavenDep): MavenDep = {
      val fixed = (
          buildArts.artifacts.view 
          filter (_.dep == mvn.dep)
          map (a => mvn.copy(version = a.version))
        ).headOption
      fixed getOrElse mvn
    }
    defaultArtifacts map fixDep
  }
  
  def makeDependencySection(arts: BuildArtifacts): String = {
    val depStrings = for {
      MavenDep(ProjectRef(name, org, ext, classifier), version, optional, scope) <- fixArtifacts(arts)      
    } yield """
    <dependency>
      <groupId>%s</groupId>
      <artifactId>%s</artifactId>
      <version>%s</version>
      <optional>%s</optional>  
      <scope>%s</scope>
     </dependency>""" format (org, name, version, optional.toString, scope)
    
    """<dependencies>
    %s
    </dependencies>""" format (depStrings mkString "\n")
  }
  
  def makeRepositoriesSection(arts: BuildArtifacts): String =
"""<repositories>
  <repository>
    <id>dsbt-local</id>
    <url>file://%s</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>""" format(arts.localRepo.getAbsolutePath)
  
  def makePomTemplate(dependencies: BuildArtifacts) = """<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.sonatype.oss</groupId>
    <artifactId>oss-parent</artifactId>
    <version>7</version>
  </parent>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
  </properties>
	
  <groupId>org.scalatest</groupId>
  <artifactId>scalatest</artifactId>
  <version>@RELEASE@</version>
  <packaging>jar</packaging>

  <name>ScalaTest</name>
  <description>
    ScalaTest is a free, open-source testing toolkit for Scala and Java
    programmers.
  </description>
  <url>http://www.scalatest.org</url>

  <licenses>
    <license>
      <name>the Apache License, ASL Version 2.0</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

  <scm>
    <url>http://code.google.com/p/scalatest/source/browse/</url>
    <connection>scm:svn:http://scalatest.googlecode.com/svn/trunk/</connection>
    <developerConnection>
      scm:svn:http://scalatest.googlecode.com/svn/trunk/
    </developerConnection>
  </scm>

  <developers>
    <developer>
      <id>bill</id>
      <name>Bill Venners</name>
      <email>bill@artima.com</email>
    </developer>
  </developers>

  <inceptionYear>2009</inceptionYear>

  %s

  %s
</project>""" format (makeDependencySection(dependencies), makeRepositoriesSection(dependencies))}