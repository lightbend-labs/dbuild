import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.module.descriptor.ModuleDescriptor

package sbt.dbuild.hack {
object DbuildHack {
  val Load = sbt.internal.Load
  val applyCross: (String, Option[String => String]) => String =
   sbt.librarymanagement.CrossVersion.applyCross
  val defaultID: (java.io.File,String) => String =
   sbt.internal.BuildDef.defaultID
  val ExceptionCategory = sbt.ExceptionCategory
}
}
package com.typesafe.dbuild.adapter {
import java.io.File

object LoggingInterface {
  val Level = sbt.util.Level
  type Logger = sbt.util.Logger
  type LogEvent = sbt.util.LogEvent
  val ControlEvent = sbt.util.ControlEvent
  val StackTrace = sbt.internal.util.StackTrace
  type BasicLogger = sbt.internal.util.BasicLogger
}

trait StreamLoggerAdapter

object Adapter {
  val IO = sbt.io.IO
  val Path = sbt.io.Path
  type RichFile = sbt.io.RichFile
  def newIvyPaths(baseDirectory: java.io.File, ivyHome: Option[java.io.File]) =
    sbt.librarymanagement.ivy.IvyPaths(baseDirectory, ivyHome)
  type FileFilter = sbt.io.FileFilter
  def toFF = sbt.io.FileFilter.globFilter _
  val DirectoryFilter = sbt.io.DirectoryFilter
  type ExactFilter = sbt.io.ExactFilter
  type NameFilter = sbt.io.NameFilter
  type FileRepository = sbt.librarymanagement.FileRepository
  type Logger = sbt.util.Logger
  def allPaths(f:File) = sbt.io.PathFinder(f).allPaths
  val syntaxio = sbt.io.syntax
  type ModuleID = sbt.librarymanagement.ModuleID
  type Artifact = sbt.librarymanagement.Artifact
  type CrossVersion = sbt.librarymanagement.CrossVersion
  type IvyScala = sbt.librarymanagement.ScalaModuleInfo
  def interProjectResolver(k:Map[ModuleRevisionId, ModuleDescriptor]) =
    new sbt.librarymanagement.RawRepository(new sbt.internal.librarymanagement.ProjectResolver("inter-project", k), "inter-project")
  val keyIvyScala = sbt.Keys.scalaModuleInfo
  def moduleWithName(m:ModuleID, name:String) = m.withName(name)
  def moduleWithRevision(m:ModuleID, revision:String) = m.withRevision(revision)
  def moduleWithCrossVersion(m:ModuleID, cross:CrossVersion) = m.withCrossVersion(cross)
  def moduleWithExplicitArtifacts(m:ModuleID, ea:Seq[Artifact]) = m.withExplicitArtifacts(ea.toVector)
  def moduleWithExtraAttributes(m:ModuleID, ea:Map[String,String]) = m.withExtraAttributes(ea)
  def ivyScalaWithCheckExplicit(i:IvyScala, ce:Boolean) = i.withCheckExplicit(ce)
  def artifactWithClassifier(a:Artifact, cl:Option[String]) = a.withClassifier(cl)
  val crossDisabled = sbt.librarymanagement.Disabled()
  type crossDisabled = sbt.librarymanagement.Disabled
  val crossBinary = sbt.librarymanagement.Binary()
  type crossBinary = sbt.librarymanagement.Binary
  val crossFull = sbt.librarymanagement.Full()
  type crossFull = sbt.librarymanagement.Full
  type ProjectResolver = sbt.internal.librarymanagement.ProjectResolver
  type ScalaInstance = sbt.internal.inc.ScalaInstance
  val ScalaInstance = sbt.internal.inc.ScalaInstance
  val Load = sbt.dbuild.hack.DbuildHack.Load
  val applyCross = sbt.dbuild.hack.DbuildHack.applyCross
  def defaultID(base: File, prefix: String = "default") =
   sbt.dbuild.hack.DbuildHack.defaultID(base, prefix)

  def reapplySettings(newSettings: Seq[sbt.Def.Setting[_]],
    structure: sbt.internal.BuildStructure,
    log: sbt.util.Logger)(implicit display: sbt.util.Show[sbt.Def.ScopedKey[_]]): sbt.internal.BuildStructure = {
      val ru = scala.reflect.runtime.universe
      val rm = ru.runtimeMirror(getClass.getClassLoader)
      val im = rm.reflect(Load)
      val reapplySymbol = ru.typeOf[Load.type].decl(ru.TermName("reapply")).asMethod
      val reapply = im.reflectMethod(reapplySymbol)
      (if (reapplySymbol.paramLists(0).size == 3)
        reapply(newSettings, structure, log, display)
       else
        reapply(newSettings, structure, display)
      ).asInstanceOf[sbt.internal.BuildStructure]
    }

// These bits are inappropriately copied from various versions of zinc; some have been
// removed and some made private, but we need them.
// See: internal/zinc-classpath/src/main/scala/sbt/internal/inc/ScalaInstance.scala

  import java.net.{ URL, URLClassLoader }

  /** Runtime exception representing a failure when finding a `ScalaInstance`. */
  class InvalidScalaInstance(message: String, cause: Throwable)
    extends RuntimeException(message, cause)

  /** The prefix being used for Scala artifacts name creation. */
  val VersionPrefix = "version "

  private def slowActualVersion(scalaLoader: ClassLoader)(label: String) = {
    val scalaVersion = {
      try {
        // Get scala version from the `Properties` file in Scalac
        Class
          .forName("scala.tools.nsc.Properties", true, scalaLoader) 
          .getMethod("versionString")
          .invoke(null)
          .toString
      } catch {
        case cause: Exception =>
          val msg = s"Scala instance doesn't exist or is invalid: $label"
          throw new InvalidScalaInstance(msg, cause)
      }
    }

    if (scalaVersion.startsWith(VersionPrefix))
      scalaVersion.substring(VersionPrefix.length)
    else scalaVersion
  }  

  private def fastActualVersion(scalaLoader: ClassLoader): String = {
    val stream = scalaLoader.getResourceAsStream("compiler.properties")
    try {
      val props = new java.util.Properties  
      props.load(stream)
      props.getProperty("version.number")  
    } finally stream.close()
  }

  /** Gets the version of Scala in the compiler.properties file from the loader.*/
  private def actualVersion(scalaLoader: ClassLoader)(label: String) = {
    try fastActualVersion(scalaLoader)
    catch { case e: Exception => slowActualVersion(scalaLoader)(label) }
  }

  private def scalaLoader(launcher: xsbti.Launcher): Seq[File] => ClassLoader = { jars =>
    import java.net.{ URL, URLClassLoader }
    new URLClassLoader(
      jars.map(_.toURI.toURL).toArray[URL],
      launcher.topLoader
    )
  }

  private def scalaInstanceHelper(libraryJar: File, compilerJar: File, extraJars: File*)(classLoader: List[File] => ClassLoader): ScalaInstance =
    {
      val loader = classLoader(libraryJar :: compilerJar :: extraJars.toList)
      val version = actualVersion(loader)(" (library jar  " + libraryJar.getAbsolutePath + ")")
      new ScalaInstance(version, loader, libraryJar, compilerJar, extraJars.toArray, None)
    }

  def scalaInstance(libraryJar: File, compilerJar: File, launcher: xsbti.Launcher, extraJars: File*): ScalaInstance =
    scalaInstanceHelper(libraryJar, compilerJar, extraJars: _*)(scalaLoader(launcher))

}
}
