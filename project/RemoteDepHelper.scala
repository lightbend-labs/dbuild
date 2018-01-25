import sbt._
import Keys._
import Dependencies._

// DSL for adding remote deps like local deps.
class RemoteDepHelper(p: Project) {
  def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  def dependsOnSbt(ms: (String=>ModuleID)*): Project = dependsOnSbtP(false, false, ms:_*)
  def dependsOnSbtProvided(ms: (String=>ModuleID)*): Project = dependsOnSbtP(true, false, ms:_*)
  def dependsOnSbtProvidedIt(ms: (String=>ModuleID)*): Project = dependsOnSbtP(true, true, ms:_*)
  def dependsOnSbtP(provided: Boolean, itOnly: Boolean, ms: (String=>ModuleID)*): Project =
    p.settings(libraryDependencies ++= {
      val v = (sbtVersion in pluginCrossBuild).value
      ms map { lib =>
        val d = lib(v)
        if (itOnly && !provided) (d % "it") else
        if (provided && !itOnly) (d % "provided") else
        if (provided && itOnly) (d % "it,provided") else d
      }
    })
}
object RemoteDepHelper {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
}

