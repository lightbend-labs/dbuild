import sbt._
import Keys._
import Dependencies._

// DSL for adding remote deps like local deps.
class RemoteDepHelper(p: Project) {
  def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  def dependsOnSbt(ms: ((String,String)=>ModuleID)*): Project =
    p.settings(libraryDependencies ++= {
      val sv = scalaVersion.value
      val v = sbtVersion.value
      ms map {_(v, sv)}
    })
  def dependsOnAkka(): Project =
    p.settings(libraryDependencies += {
      val sv = scalaVersion.value
      if (sv.startsWith("2.10")) akkaActor210 else akkaActor211
    })
  def dependsIf211(ms: (String => ModuleID)*): Project = p.settings(libraryDependencies ++= {
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if (sv.startsWith("2.11")) ms map {_(v)} else Seq.empty
  })
}
object RemoteDepHelper {
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
}

