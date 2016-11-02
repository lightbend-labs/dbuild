import sbt._
import Keys._
import Dependencies._

// DSL for adding remote deps like local deps.
class RemoteDepHelper(p: Project) {
  def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  def dependsOnSbt(ms: (String=>ModuleID)*): Project =
    p.settings(libraryDependencies ++= {
      val v = sbtVersion.value
      ms map {_(v)}
    })
}
object RemoteDepHelper {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
}

