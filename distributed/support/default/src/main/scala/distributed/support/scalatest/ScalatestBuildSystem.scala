package distributed
package support
package scalatest

import project.BuildSystem
import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import logging.Logger
import sys.process._


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
  private[this] def scalatest = ProjectDep("scalatest", group)
  private[this] def scalaOrg = "org.scala-lang"
  private[this] def scalaLibrary = ProjectDep("scala-library", scalaOrg)
  private[this] def scalaCompiler = ProjectDep("scala-compiler", scalaOrg)
  private[this] def testInterface = ProjectDep("test-interface", "org.scala-tools.testing")
  private[this] def antlrStringTemplate = ProjectDep("stringtemplate", "org.antlr")
  private[this] def scalacheck = ProjectDep("scalacheck", "org.scalacheck")
  private[this] def easymockclassext = ProjectDep("easymockclassextension", "org.easymock")
  private[this] def jmock = ProjectDep("jmock-legacy", "org.jmock")
  private[this] def mockito = ProjectDep("mockito-all", "org.mockito")
  private[this] def testng = ProjectDep("testng", "org.testng")
  private[this] def guice = ProjectDep("guice", "com.google.inject")
  private[this] def junit = ProjectDep("junit", "junit")
  private[this] def cobertura = ProjectDep("covertura", "net.sourceforge.cobertura")
  private[this] def commonsIo = ProjectDep("commons-io", "org.apache.commons")
  
  
}