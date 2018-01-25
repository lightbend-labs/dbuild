import sbt._
import Keys._

import Dependencies._

class FlexiDep
case class FlexiModule(lib: ModuleID) extends FlexiDep
case class FlexiBModule(lib: Boolean => ModuleID) extends FlexiDep
case class FlexiBModuleOpt(lib: Boolean => Option[ModuleID]) extends FlexiDep

// DSL for adding remote deps like local deps.
class RemoteDepHelper(p: Project) {
  type SbtDep = (Boolean,String) => ModuleID
  def dependsOnRemote(ms: FlexiDep*): Project = p.settings(libraryDependencies ++= {
    val scala212 = scalaVersion.value.startsWith("2.12")
    (ms map {
      case FlexiModule(l) => Some(l)
      case FlexiBModule(sl) => Some(sl(scala212))
      case FlexiBModuleOpt(slo) => slo(scala212)
    }).flatten
  })
  def dependsOnSbt(ms: SbtDep*): Project = dependsOnSbtP(false, false, ms:_*)
  def dependsOnSbtProvided(ms: SbtDep*): Project = dependsOnSbtP(true, false, ms:_*)
  def dependsOnSbtProvidedOpt(fms: (Boolean, String) => Option[sbt.ModuleID]): Project = {
    p.settings(libraryDependencies ++= {
      fms(scalaVersion.value.startsWith("2.12"), (sbtVersion in pluginCrossBuild).value) map { d =>
        d % "provided"
      }
    }.toSeq)
  }
  def dependsOnSbtProvidedIt(ms: SbtDep*): Project = dependsOnSbtP(true, true, ms:_*)
  def dependsOnSbtP(provided: Boolean, itOnly: Boolean, ms: SbtDep*): Project =
    p.settings(libraryDependencies ++= {
      val v = (sbtVersion in pluginCrossBuild).value
      val scala212 = scalaVersion.value.startsWith("2.12")
      ms map { lib =>
        val d = lib(scala212, v)
        if (itOnly && !provided) (d % "it") else
        if (provided && !itOnly) (d % "provided") else
        if (provided && itOnly) (d % "it,provided") else d
      }
    })
}
object RemoteDepHelper {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  implicit def m2flexi(lib: ModuleID): FlexiModule = new FlexiModule(lib)
  implicit def m2bflexi(lib: Boolean => ModuleID): FlexiBModule = new FlexiBModule(lib)
  implicit def m2bflexiopt(lib: Boolean => Option[ModuleID]): FlexiBModuleOpt = new FlexiBModuleOpt(lib)
}

