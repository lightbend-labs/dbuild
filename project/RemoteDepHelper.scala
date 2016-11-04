import sbt._
import Keys._
import Dependencies._
import SyntaxAdapter.syntax._

// DSL for adding remote deps like local deps.
class RemoteDepHelper(p: Project) {
  def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  def dependsOnSbt(ms: (String=>ModuleID)*): Project = dependsOnSbtP(false, ms:_*)
  def dependsOnSbtProvided(ms: (String=>ModuleID)*): Project = dependsOnSbtP(true, ms:_*)
  def dependsOnSbtP(provided: Boolean, ms: (String=>ModuleID)*): Project =
    p.settings(libraryDependencies ++= {
      val v = sbtVersion.value
      ms map { lib =>
        val d = lib(v)
        if (provided) (d % "provided") else d
      }
    })
}
object RemoteDepHelper {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
}

