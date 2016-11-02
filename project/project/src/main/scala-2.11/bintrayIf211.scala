import sbt._
import syntax._
object BintrayDep211 {
  lazy val bintrayProject = sbt.RootProject(uri("git://github.com/eed3si9n/bintray-sbt#topic/sbt1.0.0-M4"))
  val bintrayIf211 = Seq[sbt.internal.util.Eval[sbt.ClasspathDep[sbt.ProjectReference]]](bintrayProject)
}

